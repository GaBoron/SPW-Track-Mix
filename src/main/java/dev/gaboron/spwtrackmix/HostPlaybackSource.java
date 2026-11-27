package dev.gaboron.spwtrackmix;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;

/** Resolves the current local source from the Steam 1.19.3 playback graph. */
final class HostPlaybackSource {
    private final Field service;
    private final Method player;
    private final Field activeSource;
    private final Method handle;
    private final Method sourceTrack;
    private final Method mediaItem;
    private final Method path;

    HostPlaybackSource(ClassLoader loader) throws ReflectiveOperationException {
        var controller = Class.forName("com.xuncorp.voxzen.service.PlaybackController", false, loader);
        service = accessible(controller, "service");
        player = service.getType().getMethod("getPlayer");
        var bassPlayer = Class.forName("com.xuncorp.pisces.\u0528", false, loader).asSubclass(player.getReturnType());
        activeSource = accessible(bassPlayer, "\u0560");
        var source = activeSource.getType();
        handle = source.getMethod("\u037f");
        if (handle.getReturnType() != int.class) {
            throw new NoSuchMethodException("The playback source no longer exposes a BASS handle");
        }
        sourceTrack = source.getMethod("\u052e");
        mediaItem = sourceTrack.getReturnType().getMethod("\u037f");
        path = mediaItem.getReturnType().getMethod("\u052c");
        if (path.getReturnType() != String.class) {
            throw new NoSuchMethodException("The playback source no longer exposes a local path");
        }
    }

    TrackMixSession.Source current() throws ReflectiveOperationException {
        var playback = service.get(null);
        if (playback == null) return null;
        var currentPlayer = player.invoke(playback);
        if (!activeSource.getDeclaringClass().isInstance(currentPlayer)) return null;
        var source = activeSource.get(currentPlayer);
        if (source == null) return null;
        var track = sourceTrack.invoke(source);
        if (track == null) return null;
        var item = mediaItem.invoke(track);
        if (item == null) return null;
        var filename = (String) path.invoke(item);
        if (filename == null) return null;
        try {
            var local = Path.of(filename);
            int channel = (int) handle.invoke(source);
            // A transition may replace the source while these fields are being read.
            if (local.isAbsolute() && channel != 0 && sourceTrack.invoke(source) == track
                    && activeSource.get(currentPlayer) == source) {
                return new TrackMixSession.Source(local.normalize(), channel);
            }
        } catch (InvalidPathException ignored) {
            // This source is an external URL or is not backed by a local file.
        }
        return null;
    }

    private static Field accessible(Class<?> owner, String name) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (!field.trySetAccessible()) {
            throw new IllegalAccessException("Cannot read the current SPW playback source");
        }
        return field;
    }
}
