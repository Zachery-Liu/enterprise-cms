package com.zachery.cms.modules.identity.persistence;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zachery.cms.modules.identity.persistence.entity.*;
import com.zachery.cms.modules.identity.persistence.mapper.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@org.springframework.test.context.TestPropertySource(properties = "cms.db.url=jdbc:h2:mem:identitymapperintegrationtest;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000")
@ActiveProfiles("test")
@Transactional
@org.springframework.test.context.ContextHierarchy({
    @org.springframework.test.context.ContextConfiguration(name = "root", classes = {com.zachery.cms.config.RootConfiguration.class, com.zachery.cms.support.TestDatabaseConfiguration.class}),
    @org.springframework.test.context.ContextConfiguration(name = "web", classes = {com.zachery.cms.config.WebConfiguration.class, com.zachery.cms.support.MockMvcConfiguration.class})
})
class IdentityMapperIntegrationTest extends com.zachery.cms.support.SpringWebIntegrationTest {
    @Autowired private UserMapper userMapper;
    @Autowired private RoleMapper roleMapper;
    @Autowired private PermissionMapper permissionMapper;
    @Autowired private UserRoleMapper userRoleMapper;
    @Autowired private RolePermissionMapper rolePermissionMapper;
    @Autowired private OperationLogMapper operationLogMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void mapsAllIdentityTablesAndLoadsEffectivePermissions() {
        UserEntity user = newUser("editor-map", "encoded-secret");
        assertThat(userMapper.insert(user)).isOne();
        assertThat(user.getId()).isPositive();
        assertThat(user.getCreatedAt()).isNotNull();

        RoleEntity role = new RoleEntity();
        role.setCode("EDITOR_MAP");
        role.setName("Mapper editor");
        role.setStatus("ENABLED");
        role.setBuiltIn(0);
        assertThat(roleMapper.insert(role)).isOne();

        PermissionEntity permission = new PermissionEntity();
        permission.setCode("cms:test:map");
        permission.setName("Mapper permission");
        permission.setStatus("ENABLED");
        permission.setBuiltIn(0);
        assertThat(permissionMapper.insert(permission)).isOne();

        UserRoleEntity userRole = new UserRoleEntity();
        userRole.setUserId(user.getId());
        userRole.setRoleId(role.getId());
        userRole.setCreatedBy(user.getId());
        assertThat(userRoleMapper.insert(userRole)).isOne();

        RolePermissionEntity rolePermission = new RolePermissionEntity();
        rolePermission.setRoleId(role.getId());
        rolePermission.setPermissionId(permission.getId());
        rolePermission.setCreatedBy(user.getId());
        assertThat(rolePermissionMapper.insert(rolePermission)).isOne();

        OperationLogEntity log = new OperationLogEntity();
        log.setOperatorId(user.getId());
        log.setOperatorName(user.getDisplayName());
        log.setModule("IDENTITY");
        log.setAction("MAPPER_TEST");
        log.setTargetType("USER");
        log.setTargetId(user.getId());
        log.setResult("SUCCESS");
        log.setRequestId("b04-mapper-test");
        assertThat(operationLogMapper.insert(log)).isOne();
        assertThat(operationLogMapper.selectById(log.getId()).getAction()).isEqualTo("MAPPER_TEST");

        assertThat(permissionMapper.selectEffectivePermissionCodes(user.getId()))
                .containsExactly("cms:test:map");
        assertThat(userRoleMapper.selectCount(new QueryWrapper<>())).isOne();
        assertThat(rolePermissionMapper.selectCount(new QueryWrapper<>())).isOne();
    }

    @Test
    void supportsUpdateOptimisticLockAndLogicalDelete() throws Exception {
        UserEntity user = newUser("lifecycle-map", "never-serialize-this-hash");
        assertThat(userMapper.insert(user)).isOne();

        UserEntity loaded = userMapper.selectById(user.getId());
        LocalDateTime firstUpdatedAt = loaded.getUpdatedAt();
        loaded.setDisplayName("Updated mapper user");
        assertThat(userMapper.updateById(loaded)).isOne();

        UserEntity updated = userMapper.selectById(user.getId());
        assertThat(updated.getDisplayName()).isEqualTo("Updated mapper user");
        assertThat(updated.getVersion()).isEqualTo(1);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(firstUpdatedAt);

        UserEntity stale = new UserEntity();
        stale.setId(user.getId());
        stale.setDisplayName("Stale update");
        stale.setVersion(0);
        assertThat(userMapper.updateById(stale)).isZero();

        String serialized = objectMapper.writeValueAsString(updated);
        assertThat(serialized).doesNotContain("passwordHash", "never-serialize-this-hash");

        assertThat(userMapper.deleteById(user.getId())).isOne();
        assertThat(userMapper.selectById(user.getId())).isNull();
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM sys_user WHERE id = ?", Integer.class, user.getId());
        assertThat(deleted).isEqualTo(1);
    }

    private static UserEntity newUser(String username, String passwordHash) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setPasswordHash(passwordHash);
        user.setDisplayName("Mapper user");
        user.setStatus("ACTIVE");
        user.setPasswordChangedAt(LocalDateTime.now());
        return user;
    }
}
