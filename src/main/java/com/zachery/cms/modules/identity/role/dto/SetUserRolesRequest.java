package com.zachery.cms.modules.identity.role.dto;

import java.util.List;

public record SetUserRolesRequest(List<Long> roleIds) {}
