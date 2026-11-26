package com.zachery.cms.modules.media;

import com.zachery.cms.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.util.Locale;
import java.util.Map;

final class ImageFiles {
    static final int LIMIT = 5 * 1024 * 1024;
    static final Map<String,String> TYPES = Map.of("jpg","jpeg","jpeg","jpeg","png","png","webp","webp");
    record Checked(byte[] bytes, String type, int width, int height) {}

    static Checked inspect(MultipartFile file) throws IOException {
        if (file.getSize() > LIMIT) throw invalid(HttpStatus.PAYLOAD_TOO_LARGE, "图片不能超过 5 MiB");
        String name = file.getOriginalFilename();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String format = dot < 0 ? null : TYPES.get(name.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (format == null || !("image/" + format).equals(file.getContentType()))
            throw invalid(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "请选择 JPEG、PNG 或 WebP 图片");
        byte[] bytes;
        try (var stream = file.getInputStream()) { bytes = stream.readNBytes(LIMIT + 1); }
        if (bytes.length > LIMIT) throw invalid(HttpStatus.PAYLOAD_TOO_LARGE, "图片不能超过 5 MiB");
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            javax.imageio.ImageReader reader;
            if (format.equals("webp")) {
                // Tomcat may initialize ImageIO before the WAR's providers are visible.
                var provider = new com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi();
                if (!provider.canDecodeInput(stream)) throw invalid(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件内容与 WebP 类型不符");
                reader = provider.createReaderInstance();
            } else {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw invalid(HttpStatus.BAD_REQUEST, "无法识别图片");
                reader = readers.next();
            }
            try {
                if (!format.equalsIgnoreCase(reader.getFormatName()))
                    throw invalid(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "文件内容与图片类型不符");
                reader.setInput(stream, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > 8192 || h > 8192 || (long) w*h > 20000000)
                    throw invalid(HttpStatus.BAD_REQUEST, "图片宽高或像素数超限");
                reader.addIIOReadWarningListener((source, warning) -> { throw invalid(HttpStatus.BAD_REQUEST, "图片数据不完整"); });
                reader.read(0).flush();
                return new Checked(bytes, "image/" + format, w, h);
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException | IndexOutOfBoundsException damaged) {
            throw invalid(HttpStatus.BAD_REQUEST, "图片文件损坏");
        }
    }
    static BusinessException invalid(HttpStatus status, String message) {
        return new BusinessException(status, "MEDIA_" + status.value(), message);
    }
}
