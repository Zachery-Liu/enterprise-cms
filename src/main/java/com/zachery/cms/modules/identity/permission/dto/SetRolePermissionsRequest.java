package com.zachery.cms.modules.identity.permission.dto;

import java.util.List;

public record SetRolePermissionsRequest(List<Long> permissionIds) {}
