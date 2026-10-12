package org.shen.graduationproject.infrastructure;

import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.*;

import org.shen.platform.support.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 导入原文件的受控本机存储。
 * 客户端文件名只作为显示名称，实际路径由服务端 UUID 键生成，目录 700、文件 600。
 * 保存摘要用于确认时检测文件变化；本地文件系统不随数据库事务自动回滚，失败需显式清理。
 */
@Component
@Profile("business")
public class ImportStorage {
    public record Saved(String key, String filename, String hash) {
    }

    private final Path root;

    public ImportStorage(@Value("${IMPORT_STORAGE_DIR}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
            Files.setPosixFilePermissions(this.root, PosixFilePermissions.fromString("rwx------"));
        } catch (Exception unavailable) {
            throw new IllegalStateException("Import storage unavailable");
        }
    }

    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    /**
     * 只接受服务端生成的 UUID 与允许的扩展名；规范化后检查根目录边界，并拒绝符号链接。
     */
    private Path path(String key) {
        if (!key.matches("[a-f0-9-]{36}\\.(json|xlsx|xls)"))
            throw new BusinessException(503, "INVALID_STORAGE_KEY", "导入文件不可读取");
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root) || Files.isSymbolicLink(path))
            throw new BusinessException(503, "INVALID_STORAGE_KEY", "导入文件不可读取");
        return path;
    }

    /**
     * 限制上传大小与类型，剥离原始文件名中的目录部分，再以随机存储键创建私密文件。
     */
    public Saved save(MultipartFile upload) {
        String filename = upload.getOriginalFilename();
        if (filename == null || filename.length() > 255 || upload.isEmpty() || upload.getSize() > 2097152)
            throw new BusinessException(400, "INVALID_IMPORT_FILE", "需要不超过 2MB 的 Excel/JSON 文件");
        filename = filename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1);
        String ext = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT) : "";
        if (!Set.of(".json", ".xlsx", ".xls").contains(ext))
            throw new BusinessException(400, "INVALID_IMPORT_FILE", "仅支持 Excel/JSON 文件");
        String key = UUID.randomUUID() + ext;
        try {
            byte[] bytes = upload.getBytes();
            Path file = path(key);
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.write(file, bytes);
            return new Saved(key, filename, hash(bytes));
        } catch (Exception unavailable) {
            delete(key);
            throw new BusinessException(503, "IMPORT_STORAGE_UNAVAILABLE", "无法保存导入文件");
        }
    }

    /**
     * 读取时再次限制大小，防止预览后文件被替换为过大的内容；摘要一致性由确认流程另行检查。
     */
    public byte[] read(String key) {
        try {
            Path file = path(key);
            if (Files.size(file) > 2097152) throw new IllegalStateException();
            return Files.readAllBytes(file);
        } catch (Exception unavailable) {
            throw new BusinessException(503, "IMPORT_STORAGE_UNAVAILABLE", "无法读取已保存的导入文件");
        }
    }

    /**
     * 尽力清理本次失败或重复上传的临时文件；清理异常不掩盖原始业务错误。
     */
    public void delete(String key) {
        try {
            Files.deleteIfExists(path(key));
        } catch (Exception ignored) {
        }
    }
}
