package com.zachery.cms.modules.media;

import com.zachery.cms.common.context.CurrentUserContext;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

@Service
public class ImageStore {
    public record Uploaded(String id, String url, int width, int height, long size) {}
    public record Stored(String type, FileSystemResource file) {}
    private final JdbcTemplate db;
    private final CurrentUserContext users;
    private final Path directory;

    public ImageStore(JdbcTemplate db, CurrentUserContext users, @Value("${cms.media.directory:${CMS_MEDIA_DIRECTORY:}}") String configured,
                      org.springframework.beans.factory.ObjectProvider<jakarta.servlet.ServletContext> contexts)
            throws IOException {
        this.db = db;
        this.users = users;
        directory = configured.isBlank() ? null : Path.of(configured).normalize();
        if (directory != null) {
            if (!directory.isAbsolute()) throw new IllegalArgumentException("cms.media.directory must be absolute");
            Path real = Files.createDirectories(directory).toRealPath();
            var servletContext = contexts.getIfAvailable();
            String webRoot = servletContext == null ? null : servletContext.getRealPath("/");
            if ((webRoot != null && real.startsWith(Path.of(webRoot).toRealPath()))
                    || real.startsWith(Path.of(System.getProperty("java.io.tmpdir")).toRealPath()))
                throw new IllegalArgumentException("Store CMS images outside the web application and temporary directory");
            if (!Files.isWritable(directory)) throw new IOException("Media directory is not writable");
        }
    }

    @Transactional(rollbackFor = IOException.class)
    public Uploaded save(MultipartFile file, String context) throws IOException {
        long actor = users.getRequiredUser().userId();
        if (directory == null) throw ImageFiles.invalid(HttpStatus.SERVICE_UNAVAILABLE, "图片存储目录尚未配置");
        var checked = ImageFiles.inspect(file);
        String id = UUID.randomUUID().toString();
        Path path = directory.resolve(id);
        Files.createFile(path);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try { Files.deleteIfExists(path); }
                    catch (IOException failure) { org.slf4j.LoggerFactory.getLogger(ImageStore.class).error("媒体回滚清理失败 id={}", id, failure); }
                }
            }
        });
        Files.write(path, checked.bytes(), StandardOpenOption.WRITE);
        db.update("INSERT INTO cms_media_image(id,storage_key,mime_type,width,height,byte_size,uploader_id,created_at) VALUES(?,?,?,?,?,?,?,?)",
                id,id,checked.type(),checked.width(),checked.height(),checked.bytes().length,actor,
                java.time.LocalDateTime.now(java.time.Clock.systemUTC()));
        return new Uploaded(id,context + "/media/images/" + id,checked.width(),checked.height(),checked.bytes().length);
    }

    public Stored read(String id) {
        if (directory == null) throw ImageFiles.invalid(HttpStatus.SERVICE_UNAVAILABLE, "图片存储目录尚未配置");
        var types = db.queryForList("SELECT mime_type FROM cms_media_image WHERE id=? AND storage_key=?", String.class,id,id);
        Path path = directory.resolve(id);
        if (types.isEmpty() || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw ImageFiles.invalid(HttpStatus.NOT_FOUND, "图片不存在");
        return new Stored(types.get(0),new FileSystemResource(path));
    }
}
