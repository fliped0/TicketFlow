package com.ticketflow.model.entity;

import java.util.List;
import java.util.Map;

public record AsyncSnapshot(long session, long epoch, long version, String owner,
                            long start, long finish, long starts,
                            Map<String, Long> free, Map<String, Long> sequence,
                            List<AsyncToken> tokens) {}
