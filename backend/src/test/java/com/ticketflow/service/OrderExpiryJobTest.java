package com.ticketflow.service;

import com.ticketflow.mapper.OrderMapper;
import com.ticketflow.model.entity.ExpiredOrder;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderExpiryJobTest {
    @Test void persistentSlowHeadMustNotStarveLaterOrdersAcrossRounds() {
        var orders=mock(OrderMapper.class);
        var service=mock(OrderApplicationService.class);
        var clock=mock(DatabaseClock.class);
        var now=LocalDateTime.of(2026,1,1,0,0);
        when(clock.nowUtc()).thenReturn(now);
        var first=new ExpiredOrder(1,1,now.minusMinutes(2));
        var second=new ExpiredOrder(2,2,now.minusMinutes(1));
        when(orders.expired(any(),any(),anyLong())).thenAnswer(call -> {
            long after=call.getArgument(2);
            return after==0 ? List.of(first,second) : after==1 ? List.of(second) : List.of();
        });
        // The fault deliberately remains active for the second round.
        doAnswer(call -> { Thread.sleep(9_200); throw new IllegalStateException("persistent slow head"); })
                .when(service).closeExpired(eq(1L),anyLong());
        var job=new OrderExpiryJob(orders,service,clock);
        job.scan();
        verify(service,never()).closeExpired(eq(2L),anyLong());
        job.scan();
        verify(service).closeExpired(eq(2L),anyLong());
        verify(service,times(1)).closeExpired(eq(1L),anyLong());
        // A completed traversal must wrap so failures are retried, not discarded.
        doReturn(true).when(service).closeExpired(eq(1L),anyLong());
        job.scan();
        verify(service,times(2)).closeExpired(eq(1L),anyLong());
    }
}
