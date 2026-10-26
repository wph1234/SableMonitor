package dev.wphuujmhu.sablemonitor.natives;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Loads SableMonitor's patched build of {@code sable_rapier} instead of Sable's stock one.
 * <p>
 * Sable unpacks its native library from its own jar into {@code .sable/natives/} and then calls
 * {@link System#load(String)} on it, every time {@code Rapier3D} is class-initialised. That means
 * a patched DLL dropped on disk would be overwritten before it could ever be loaded. The fix is
 * {@link dev.wphuujmhu.sablemonitor.mixin.RapierNativeLoaderMixin}, which runs at the head of
 * Sable's {@code loadLibrary()} and, if a patched library is bundled with this mod, extracts and
 * loads that one instead and cancels Sable's loader.
 * <p>
 * When the patched library is missing (e.g. a production build that only ships Java), everything
 * falls back to Sable's own natives and the profiler simply reports itself as unavailable.
 */
public final class PatchedNatives {
    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Fallback directory, relative to the process working directory.
     * <p>
     * Sable 2.0.5 resolves this through {@code SableLoaderPlatform.getGameDirectory()}, so
     * {@link #nativeDir()} asks Sable first and only falls back to this literal path.
     */
    public static final String NATIVE_DIR = ".sable/natives";

    /** Where this mod bundles the patched library inside its jar. */
    public static final String RESOURCE_DIR = "/natives/sable_rapier/";

    private static volatile boolean attempted;
    private static volatile boolean loaded;

    private PatchedNatives() {
    }

    /**
     * @return {@code true} if the patched library is loaded and its extra JNI symbols are usable.
     */
    public static boolean isLoaded() {
        return loaded;
    }

    /**
     * Attempts to extract + load the patched library. Safe to call more than once.
     *
     * @return {@code true} if the patched library is the one currently loaded.
     */
    public static synchronized boolean tryLoad() {
        if (attempted) {
            return loaded;
        }

        attempted = true;

        final String fileName = fileName();
        final String resource = RESOURCE_DIR + fileName;

        try (final InputStream in = PatchedNatives.class.getResourceAsStream(resource)) {
            if (in == null) {
                LOGGER.warn("no patched rapier natives bundled ({}); " +
                        "falling back to Sable's stock library, /sablemonitor will report no data", resource);
                return false;
            }

            final Path dir = nativeDir();

            if (!Files.exists(dir)) {
                Files.createDirectories(dir);
            }

            // Content-addressed file name. Two reasons:
            //  - a stale process (or Sable itself) may still hold a lock on the plain name, and
            //    overwriting a loaded DLL fails with AccessDeniedException on Windows;
            //  - the same build always maps to the same name, so we never re-extract needlessly.
            final Path target = extract(dir, readAll(in), fileName);

            System.load(target.toAbsolutePath().toString());

            loaded = true;
            LOGGER.info("loaded patched rapier natives from {}", target.toAbsolutePath());
            return true;
        } catch (final Throwable t) {
            LOGGER.error("failed to load patched rapier natives, " +
                    "falling back to Sable's stock library", t);
            return false;
        }
    }

    /**
     * Extracts {@code bytes} next to Sable's natives under a name that includes a hash of the
     * library, and returns that path.
     */
    private static Path extract(final Path dir, final byte[] bytes, final String plainName) throws IOException {
        final Path target = dir.resolve(versionedFileName(bytes, plainName));

        if (!Files.exists(target)) {
            Files.write(target, bytes);
        }

        return target;
    }

    /**
     * Where Sable keeps its natives: {@code <game dir>/.sable/natives}, or {@code .sable/natives}
     * relative to the working directory if Sable's platform API cannot be queried.
     */
    private static Path nativeDir() {
        try {
            final Class<?> platform = Class.forName("dev.ryanhcode.sable.platform.SableLoaderPlatform");
            final Object instance = platform.getField("INSTANCE").get(null);
            final Object gameDir = platform.getMethod("getGameDirectory").invoke(instance);

            if (gameDir instanceof final Path path) {
                return path.resolve(".sable").resolve("natives");
            }
        } catch (final Throwable ignored) {
            // Older Sable builds (2.0.3 and earlier) have no such API; use the literal path.
        }

        return Paths.get(NATIVE_DIR);
    }

    private static String versionedFileName(final byte[] bytes, final String plainName) {
        final int dot = plainName.lastIndexOf('.');

        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(bytes);
            final StringBuilder sb = new StringBuilder();

            // 8 hex chars is plenty to tell builds apart; collisions only cost an extra extract.
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02x", hash[i]));
            }

            final String stem = dot > 0 ? plainName.substring(0, dot) : plainName;
            final String ext = dot > 0 ? plainName.substring(dot) : "";

            return stem + "-" + sb + ext;
        } catch (final NoSuchAlgorithmException e) {
            return plainName;
        }
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 21);
        in.transferTo(out);
        return out.toByteArray();
    }

    /**
     * Mirrors Sable's own file-naming logic so the extracted file looks exactly like the one
     * Sable would have written.
     */
    public static String fileName() {
        final String osArch = System.getProperty("os.arch", "");
        final String arch = osArch.equals("arm") || osArch.startsWith("aarch64") ? "aarch64" : "x86_64";

        final String osName = System.getProperty("os.name", "").toLowerCase();

        final String suffix;
        if (osName.contains("win")) {
            suffix = "windows.dll";
        } else if (osName.contains("mac") || osName.contains("darwin")) {
            suffix = "macos.dylib";
        } else {
            suffix = "linux.so";
        }

        return "sable_rapier_" + arch + "_" + suffix;
    }
}
