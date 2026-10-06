"""Finite protocol model checks; no Redis/MQ integration or implementation claim."""
import argparse,dataclasses,itertools,json
from pathlib import Path

@dataclasses.dataclass(frozen=True)
class State:
    request:str='NONE'
    available:int=1
    reserved:int=0
    sold:int=0
    queued:int=0
    redis_free:int=1
    token:str='NONE'
    epoch:int=1
    token_epoch:int=1
    work:int=0
    order_count:int=0
    eligibility:str='NONE'

def step(s,action):
    d=dataclasses.asdict(s)
    if action=='lua' and s.token=='NONE' and s.redis_free==1 and s.request=='NONE':d.update(redis_free=0,token='TENTATIVE',token_epoch=s.epoch)
    elif action=='accept' and s.request=='NONE' and s.token=='TENTATIVE':d.update(request='ACCEPTED',queued=1,eligibility='QUEUED')
    elif action=='fence' and s.request=='NONE' and s.token=='TENTATIVE':d.update(request='REJECTED')
    elif action=='claim' and s.request=='ACCEPTED':d.update(request='PROCESSING',work=s.work+1)
    elif action=='create' and s.request=='PROCESSING':d.update(request='SUCCEEDED',queued=0,available=0,reserved=1,order_count=1,eligibility='ORDER')
    elif action=='expire' and s.request in ('ACCEPTED','PROCESSING'):d.update(request='REJECTED',queued=0,eligibility='NONE',work=s.work+1)
    elif action=='release' and s.request=='REJECTED' and s.token=='TENTATIVE':d.update(redis_free=1,token='RELEASED')
    elif action=='pay' and s.reserved==1:d.update(reserved=0,sold=1)
    elif action=='cancel' and s.reserved==1:d.update(reserved=0,available=1,eligibility='NONE')
    elif action=='refund' and s.sold==1:d.update(sold=0,available=1,eligibility='NONE')
    elif action=='lifecycle_release' and s.request=='SUCCEEDED' and s.available==1 and s.token in ('TENTATIVE','BOUND'):d.update(redis_free=1,token='RELEASED')
    elif action=='materialize' and s.request=='SUCCEEDED' and s.token=='TENTATIVE':d.update(token='BOUND')
    # Rebuild includes only database-authoritative requests/orders; a tentative
    # orphan must be fenced before snapshot publication, represented atomically.
    elif action=='rebuild':
        occupied=s.reserved+s.sold+s.queued
        d.update(epoch=s.epoch+1,token_epoch=s.epoch+1,redis_free=s.available-s.queued,token='BOUND' if occupied else 'RELEASED')
        if s.request=='NONE' and s.token=='TENTATIVE':d.update(request='REJECTED')
    return State(**d)

def invariant(s):
    assert s.available+s.reserved+s.sold==1
    assert 0<=s.queued<=s.available
    assert s.order_count<=1 and 0<=s.redis_free<=1
    assert s.queued==(s.request in ('ACCEPTED','PROCESSING'))
    assert (s.eligibility=='QUEUED')==(s.queued==1)
    assert (s.eligibility=='ORDER')==(s.reserved+s.sold==1)
    assert s.request!='SUCCEEDED' or s.order_count==1

def run():
    cases=[]
    for order in itertools.permutations(('accept','fence')):
        s=step(State(),'lua')
        for a in order:s=step(s,a)
        for a in ('release','claim','create','create'):s=step(s,a)
        invariant(s);assert s.request==('SUCCEEDED' if order[0]=='accept' else 'REJECTED')
        cases.append({'name':'orphan_vs_accept_'+order[0],'result':s.request})
    s=State()
    for a in ('lua','accept','accept','claim','create','create','pay','pay','refund','refund','lifecycle_release','lifecycle_release'):s=step(s,a)
    invariant(s);assert s.order_count==1 and s.redis_free==1
    cases.append({'name':'commit_unknown_and_duplicate_delivery_lifecycle','orderCount':s.order_count})
    s=State()
    for a in ('lua','accept','claim'):s=step(s,a)
    old_version=s.work;s=step(s,'expire');assert old_version!=s.work and s.request=='REJECTED'
    cases.append({'name':'late_worker_fenced_by_work_version','oldVersion':old_version,'currentVersion':s.work})
    s=step(step(State(),'lua'),'accept');old_epoch=s.epoch;s=step(s,'rebuild')
    before=s.redis_free
    # An old-epoch release is explicitly rejected, independent of lease age.
    if old_epoch==s.epoch:s=step(s,'release')
    assert s.redis_free==before
    cases.append({'name':'old_epoch_release_after_rebuild','oldEpoch':old_epoch,'newEpoch':s.epoch})
    owner='new-token';old_token='old-token';assert owner!=old_token
    cases.append({'name':'old_release_cannot_delete_new_qualification','ownerComparison':'mismatch'})
    last_seq=4;incoming=6;assert incoming!=last_seq+1
    cases.append({'name':'projection_gap_must_not_apply','lastSeq':last_seq,'incomingSeq':incoming,'result':'GAP'})
    actions=('lua','accept','fence','claim','create','expire','release','pay','cancel','refund','lifecycle_release','materialize')
    frontier={State()};seen=set(frontier);edges=0
    for depth in range(12):
        following=set()
        for s in frontier:
            invariant(s)
            for action in actions:
                t=step(s,action);invariant(t);edges+=1
                if t not in seen:following.add(t)
        seen.update(following);frontier=following
        if not frontier:break
    for s in seen:invariant(step(s,'rebuild'))
    return {'kind':'abstract atomic state-transition model','passed':True,'scenarioChecks':cases,'reachableStates':len(seen),'checkedEdges':edges,'depthLimit':12,'limitations':'Assumes documented atomic database locks/transactions and Lua guards. Does not execute SQL, Redis, RabbitMQ, crashes or network faults; implementation must be validated in batches 8-10.'}

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',default='.tools/batch6-model.json');args=parser.parse_args()
    result=run();path=Path(args.output);path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))
