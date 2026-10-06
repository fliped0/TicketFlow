"""Local V1 staircase screening, not the three-round cloud comparison.
Uses batch5's isolated JVM/keys/config helpers. No database reset or cloud access.
Python 3.12 + requests, Java 17 and MySQL CLI are required.
"""
import argparse,concurrent.futures as futures,csv,datetime as dt,gzip,hashlib,importlib.util,json,math,os,statistics,subprocess,threading,time,uuid
from pathlib import Path
import requests

ROOT=Path(__file__).resolve().parents[1]
spec=importlib.util.spec_from_file_location('batch5',ROOT/'scripts/batch5-experiments.py')
b5=importlib.util.module_from_spec(spec);spec.loader.exec_module(b5)

def percentile(values,q):
    ordered=sorted(values)
    return round(ordered[max(0,math.ceil(len(ordered)*q)-1)],3) if ordered else None

class Baseline:
    def __init__(self,args):
        self.args=args
        os.environ['TF_OBSERVABILITY_ENABLED']='true'
        self.exp=b5.Experiments(args)
        self.report={'runId':self.exp.marker,'startedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'kind':'local staircase screening','warmupSeconds':args.warmup,'measurementSeconds':args.measurement,'roundsPerPoint':1,'environment':self.exp.results['environment'],'points':[],'observations':[],'unavailable':{}}
        rows=self.ps('Get-Process mysqld -ErrorAction SilentlyContinue | Select-Object Id')
        self.mysql_pids=[p['Id'] for p in ([rows] if isinstance(rows,dict) else rows or [])]
        self.lock=threading.Lock();self.stopped=threading.Event();self.stage='prepare'
        self.schema_enabled=True
    def ps(self,command):
        p=subprocess.run(['powershell','-NoProfile','-Command',command+' | ConvertTo-Json -Compress'],capture_output=True,text=True,creationflags=b5.HIDDEN)
        return json.loads(p.stdout) if p.returncode==0 and p.stdout.strip() else None
    def persist(self):
        Path(self.args.output).write_text(json.dumps(self.report,indent=2),encoding='utf-8')
    def observe(self):
        previous={};last=time.perf_counter()
        while not self.stopped.wait(5):
            began=time.perf_counter();entry={'observedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'stage':self.stage}
            ids=[os.getpid(),self.exp.process.pid]+self.mysql_pids
            procs=self.ps('Get-Process -Id '+','.join(map(str,ids))+' -ErrorAction SilentlyContinue | Select-Object Id,CPU,WorkingSet64,PrivateMemorySize64')
            resources=[]
            for p in ([procs] if isinstance(procs,dict) else procs or []):
                cpu=p.get('CPU');pid=p['Id']
                resources.append({'pid':pid,'role':'load_generator' if pid==os.getpid() else 'application' if pid==self.exp.process.pid else 'mysql','cpuSeconds':cpu,'cpuPercentOfMachine':round(100*(cpu-previous.get(pid,cpu))/max(.001,began-last)/os.cpu_count(),3) if cpu is not None else None,'workingSetBytes':p['WorkingSet64'],'privateBytes':p['PrivateMemorySize64']})
                if cpu is not None:previous[pid]=cpu
                else:self.report['unavailable']['mysqlCpu']='Windows service CPU counters unavailable to this account; null is not zero. All mysqld processes are listed.'
            last=began;entry['processes']=resources
            try:
                status=self.exp.db("SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Innodb_row_lock_current_waits','Innodb_row_lock_waits','Innodb_row_lock_time','Innodb_deadlocks','Queries')")
                entry['mysqlGlobalStatus']={k:int(v) for k,v in status}
                if self.schema_enabled:
                    try:
                        row=self.exp.db('SELECT COUNT(*) FROM performance_schema.data_lock_waits')[0]
                        entry['mysqlDataLockWaits']=int(row[0])
                    except Exception:
                        self.schema_enabled=False;self.report['unavailable']['mysqlDataLockWaits']='Dedicated tf_test account cannot read performance_schema; no privilege expansion. Global InnoDB counters are server-wide.'
            except Exception as error:entry['databaseCollectionError']=type(error).__name__
            with self.lock:self.report['observations'].append(entry)
    def point(self,kind,workers,corpus):
        self.stage=f'{kind}_{workers}';samples=[];cleanup=[];guard=threading.Lock();barrier=threading.Barrier(workers+1);window={}
        def call(session,name,method,path,actor=None,payload=None,key=None):
            began=time.perf_counter()
            try:status,body=self.exp.call(method,path,actor,payload,key,session=session);code=body.get('code','MISSING_CODE')
            except requests.RequestException:status,body,code=0,{},'TRANSPORT_ERROR'
            elapsed=(time.perf_counter()-began)*1000
            if window['measurementBegin']<=began<window['finish']:
                with guard:samples.append((name,round(elapsed,6),status,code))
            return status,body
        def worker(index):
            actor=self.exp.actors[index];sequence=index
            with requests.Session() as session:
                barrier.wait()
                while time.perf_counter()<window['finish']:
                    f=corpus[index] if kind=='trade' else corpus[sequence%len(corpus)]
                    if kind=='query':
                        name,path=[('event_list',f'/api/v1/events?city={self.exp.marker}&page={(sequence%50)+1}&size=20'),('event_detail',f"/api/v1/events/{f['event']}"),('session_list',f"/api/v1/events/{f['event']}/sessions"),('tier_list',f"/api/v1/sessions/{f['session']}/tiers")][sequence%4]
                        call(session,name,'GET',path)
                    else:
                        status,body=call(session,'create','POST','/api/v1/orders',actor,{'tierId':str(f['tier']),'quantity':1},uuid.uuid4().hex)
                        if status==201:
                            order=body['data']['orderId'];paid,_=call(session,'pay','POST',f'/api/v1/orders/{order}/payments',actor,{},uuid.uuid4().hex)
                            if paid==200:
                                try:closed,_=self.exp.call('POST',f'/api/v1/orders/{order}/refunds',actor,{},uuid.uuid4().hex,session=session)
                                except requests.RequestException:closed=0
                                if closed!=200:
                                    with guard:cleanup.append({'worker':index,'orderId':order,'httpStatus':closed})
                                    return
                            else:
                                with guard:cleanup.append({'worker':index,'orderId':order,'failedPhase':'pay','httpStatus':paid})
                                return
                        else:
                            with guard:cleanup.append({'worker':index,'failedPhase':'create','httpStatus':status})
                            return
                    sequence+=1
        with futures.ThreadPoolExecutor(max_workers=workers) as pool:
            tasks=[pool.submit(worker,i) for i in range(workers)]
            now=time.perf_counter();window.update(measurementBegin=now+self.args.warmup,finish=now+self.args.warmup+self.args.measurement)
            barrier.wait()
            for task in tasks:task.result()
        groups={}
        for row in samples:groups.setdefault(row[0],[]).append(row)
        stats={}
        for name,rows in groups.items():
            latencies=[v[1] for v in rows];errors=sum(s==0 or s>=500 for _,_,s,_ in rows)
            stats[name]={'requests':len(rows),'success':sum(200<=s<300 for _,_,s,_ in rows),'codes':{c:sum(v[3]==c for v in rows) for c in {v[3] for v in rows}},'systemErrors':errors,'systemErrorRate':errors/len(rows),'p95Ms':percentile(latencies,.95),'p99Ms':percentile(latencies,.99),'meanMs':round(statistics.mean(latencies),3),'successfulRequestsPerSecond':round(sum(200<=s<300 for _,_,s,_ in rows)/self.args.measurement,3)}
        target=500 if kind=='query' else 1000
        passed=bool(stats) and not cleanup and all(v['systemErrorRate']<.01 and v['p95Ms']<=target for v in stats.values())
        path=Path(self.args.output).with_name(Path(self.args.output).stem+f'-{kind}-{workers}.csv.gz')
        with gzip.open(path,'wt',encoding='utf-8',newline='') as f:
            writer=csv.writer(f);writer.writerow(['endpoint','latencyMs','httpStatus','code']);writer.writerows(samples)
        point={'kind':kind,'workers':workers,'completedAt':dt.datetime.now(dt.timezone.utc).isoformat(),'stats':stats,'screeningTargetsMet':passed,'cleanupFailures':cleanup,'sampleFile':path.name,'sampleSha256':hashlib.sha256(path.read_bytes()).hexdigest()}
        self.report['points'].append(point);self.persist();print(json.dumps(point),flush=True)
        return passed
    def run(self):
        self.exp.start(proxy=False)
        with futures.ThreadPoolExecutor(max_workers=8) as p:self.exp.actors=list(p.map(self.exp.new_actor,range(max(self.args.workers))))
        corpus=self.exp.corpus();thread=threading.Thread(target=self.observe,daemon=True);thread.start()
        try:
            for kind in ('query','trade'):
                for count in self.args.workers:
                    if kind=='trade':
                        for actor in self.exp.actors[:count]:actor['token']=self.exp.success('POST','/api/v1/auth/login',payload=actor['credentials'])['data']['accessToken']
                    if not self.point(kind,count,corpus):
                        self.report.setdefault('stoppedEscalation',[]).append({'kind':kind,'workers':count,'reason':'latency/error/eligible-load screening condition failed'});break
            self.stage='reconciliation'
            for f in corpus[:max(self.args.workers)]:self.exp.reconcile(f['tier'])
            self.report['reconciledTiers']=max(self.args.workers)
        finally:self.stopped.set();thread.join(timeout=20)
        log=self.exp.log_path.read_text(encoding='utf-8',errors='replace')
        self.report['applicationMetrics']=[json.loads(line.split('metrics_snapshot ',1)[1]) for line in log.splitlines() if 'metrics_snapshot ' in line]
        self.report['jarSha256']=hashlib.sha256(self.exp.jar.read_bytes()).hexdigest()
        self.report['completedAt']=dt.datetime.now(dt.timezone.utc).isoformat();self.report['completed']=True
        self.persist()
    def close(self):self.stopped.set();self.exp.close();os.environ.pop('TF_OBSERVABILITY_ENABLED',None)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--java',default='java.exe');p.add_argument('--mysql',default='mysql.exe')
    p.add_argument('--output',default='.tools/batch6-baseline.json');p.add_argument('--workers',type=int,nargs='+',default=[20,50,100,200])
    p.add_argument('--warmup',type=int,default=10);p.add_argument('--measurement',type=int,default=30)
    args=p.parse_args();assert 1<=min(args.workers)<=max(args.workers)<=200 and args.warmup>=1 and args.measurement>=1
    b=Baseline(args)
    try:b.run()
    except BaseException as error:b.report['failure']=type(error).__name__+': '+str(error);b.persist();raise
    finally:b.close()
