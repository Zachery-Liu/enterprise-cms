package com.zachery.cms.modules.media;

import com.zachery.cms.common.api.ApiResponse;
import com.zachery.cms.security.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
public class ImageController {
    private final ImageStore store;
    public ImageController(ImageStore store) { this.store = store; }

    @PostMapping(value="/api/media/images", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequirePermission(value="cms:article:create", alternatives="cms:article:edit")
    public ApiResponse<ImageStore.Uploaded> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) throws IOException {
        return ApiResponse.ok(store.save(file, request.getContextPath()));
    }

    @AnonymousAccess
    @GetMapping("/media/images/{id:[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}}")
    public ResponseEntity<FileSystemResource> image(@PathVariable String id) {
        var image = store.read(id);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(image.type()))
                .header("X-Content-Type-Options","nosniff").body(image.file());
    }
}
