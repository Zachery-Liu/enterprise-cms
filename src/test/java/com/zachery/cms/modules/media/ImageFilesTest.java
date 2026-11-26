package com.zachery.cms.modules.media;

import com.zachery.cms.common.exception.BusinessException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import static org.assertj.core.api.Assertions.*;

class ImageFilesTest {
    private byte[] image(String format, int width) throws Exception {
        var buffer = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, 3, BufferedImage.TYPE_INT_RGB), format, buffer);
        return buffer.toByteArray();
    }

    @Test void decodesPngAndJpegAndMeasuresActualDimensions() throws Exception {
        for (String type : new String[]{"png","jpeg"}) {
            var bytes = image(type, 4);
            var checked = ImageFiles.inspect(new MockMultipartFile("file","photo."+type,"image/"+type, bytes));
            assertThat(checked.width()).isEqualTo(4);
            assertThat(checked.height()).isEqualTo(3);
            assertThat(checked.bytes()).isEqualTo(bytes);
        }
    }

    @Test void rejectsDamagedMismatchedAndOversizeImages() throws Exception {
        byte[] png = image("png", 4);
        reject("photo.jpg", "image/jpeg", png, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        reject("photo.png", "image/jpeg", png, HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        reject("photo.png", "image/png", Arrays.copyOf(png, 24), HttpStatus.BAD_REQUEST);
        reject("photo.png", "image/png", image("png", 8193), HttpStatus.BAD_REQUEST);
        reject("photo.png", "image/png", new byte[ImageFiles.LIMIT + 1], HttpStatus.PAYLOAD_TOO_LARGE);
    }

    private void reject(String name, String mime, byte[] bytes, HttpStatus status) {
        assertThatExceptionOfType(BusinessException.class)
                .isThrownBy(() -> ImageFiles.inspect(new MockMultipartFile("file", name, mime, bytes)))
                .satisfies(e -> assertThat(e.getHttpStatus()).isEqualTo(status));
    }
}
