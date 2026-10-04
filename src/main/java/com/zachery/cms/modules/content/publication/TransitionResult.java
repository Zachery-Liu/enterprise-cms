package com.zachery.cms.modules.content.publication;

import java.time.Instant;

public record TransitionResult(Long id,String status,Integer version,Instant updatedAt) { }
