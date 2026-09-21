package com.zachery.cms.modules.identity.persistence;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class IdentityAuditFillHandler implements MetaObjectHandler {
    private final AuditActorProvider actors;

    public IdentityAuditFillHandler(AuditActorProvider actors) {
        this.actors = actors;
    }

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        actors.currentUserId().ifPresent(id -> {
            strictInsertFill(metaObject, "createdBy", Long.class, id);
            strictInsertFill(metaObject, "updatedBy", Long.class, id);
        });
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName("updatedAt", LocalDateTime.now(ZoneOffset.UTC), metaObject);
        actors.currentUserId().ifPresent(id ->
                setFieldValByName("updatedBy", id, metaObject));
    }
}
