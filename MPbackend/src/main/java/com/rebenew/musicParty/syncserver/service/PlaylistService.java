package com.rebenew.musicParty.syncserver.service;

import com.rebenew.musicParty.syncserver.core.RoomSessionManager;
import com.rebenew.musicParty.syncserver.model.RoomSession;
import com.rebenew.musicParty.syncserver.model.TrackEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class PlaylistService {
    private static final Logger logger = LoggerFactory.getLogger(PlaylistService.class);

    private final RoomSessionManager roomSessionManager;

    public PlaylistService(RoomSessionManager roomSessionManager) {
        this.roomSessionManager = roomSessionManager;
    }

    public boolean addTrack(String roomId, String trackId, String title, String addedBy, long durationMs) {
        RoomSession session = roomSessionManager.getSession(roomId);
        if (session == null) {
            logger.warn("Attempt to add track to non-existent room: {}", roomId);
            return false;
        }
        if (!session.canUserModifyPlaylist(addedBy)) {
            logger.warn("User {} has no permission to add tracks in room: {}", addedBy, roomId);
            return false;
        }

        TrackEntry track = new TrackEntry(trackId, title != null ? title : "Unknown Track", addedBy,
                System.currentTimeMillis(), durationMs);
        boolean success = session.addTrack(track, addedBy);
        if (success) {
            roomSessionManager.broadcastPlaylistUpdate(session, "add", track, null, null);
            logger.debug("🎵 Track added to room {}: '{}' by {}", roomId, title, addedBy);
        }
        return success;
    }

    public boolean replacePlaylist(String roomId, List<TrackEntry> newTracks, String sourceUserId) {
        RoomSession session = roomSessionManager.getSession(roomId);
        if (session == null)
            return false;

        boolean success = session.replacePlaylist(newTracks, sourceUserId);
        if (success) {
            roomSessionManager.broadcastSystemMessage(session, "playlist_sync", Map.of("tracks", newTracks), sourceUserId);
            logger.debug("🔄 Playlist synchronized in room {} ({} tracks) by {}", roomId, newTracks.size(),
                    sourceUserId);
        }
        return success;
    }

    public List<TrackEntry> getPlaylistCopy(String roomId) {
        RoomSession session = roomSessionManager.getSession(roomId);
        if (session == null)
            return Collections.emptyList();
        return new ArrayList<>(session.getPlaylist());
    }

    public boolean removeTrack(String roomId, int trackIndex, String removerUserId) {
        RoomSession session = roomSessionManager.getSession(roomId);
        if (session == null) {
            logger.warn("Attempt to remove track from non-existent room: {}", roomId);
            return false;
        }
        if (!session.canUserModifyPlaylist(removerUserId)) {
            logger.warn("User {} has no permission to remove tracks in room: {}", removerUserId, roomId);
            return false;
        }
        if (trackIndex < 0 || trackIndex >= session.getPlaylistSize()) {
            logger.warn("Invalid index when removing track: {} in room {}", trackIndex, roomId);
            return false;
        }
        TrackEntry removedTrack = session.getPlaylist().get(trackIndex);
        boolean success = session.removeTrack(trackIndex, removerUserId);
        if (success) {
            roomSessionManager.broadcastPlaylistUpdate(session, "remove", removedTrack, trackIndex, null);
            logger.debug("🗑️ Track removed from room {}: index {} by {}", roomId, trackIndex, removerUserId);
        }
        return success;
    }

    public boolean moveTrack(String roomId, int fromIndex, int toIndex, String moverUserId) {
        RoomSession session = roomSessionManager.getSession(roomId);
        if (session == null) {
            logger.warn("Attempt to move track in non-existent room: {}", roomId);
            return false;
        }
        if (!session.canUserModifyPlaylist(moverUserId)) {
            logger.warn("User {} has no permission to move tracks in room: {}", moverUserId, roomId);
            return false;
        }
        if (fromIndex < 0 || fromIndex >= session.getPlaylistSize() ||
                toIndex < 0 || toIndex >= session.getPlaylistSize()) {
            logger.warn("Invalid indices for moving track: {} -> {} in room {}", fromIndex, toIndex, roomId);
            return false;
        }
        TrackEntry movedTrack = session.getPlaylist().get(fromIndex);
        boolean success = session.moveTrack(fromIndex, toIndex, moverUserId);
        if (success) {
            roomSessionManager.broadcastPlaylistUpdate(session, "move", movedTrack, fromIndex, toIndex);
            logger.debug("🔀 Track moved in room {}: from {} to {} by {}", roomId, fromIndex, toIndex, moverUserId);
        }
        return success;
    }

    public boolean clearPlaylist(String roomId, String clearerUserId) {
        RoomSession session = roomSessionManager.getSession(roomId);
        if (session == null) {
            logger.warn("Attempt to clear playlist of non-existent room: {}", roomId);
            return false;
        }
        if (!session.isHost(clearerUserId)) {
            logger.warn("User {} has no permission to clear playlist in room: {}", clearerUserId, roomId);
            return false;
        }
        boolean success = session.clearPlaylist(clearerUserId);
        if (success) {
            roomSessionManager.broadcastSystemMessage(session, "playlist_cleared", Map.of("clearedBy", clearerUserId), null);
            logger.info("🧹 Playlist cleared in room {} by {}", roomId, clearerUserId);
        }
        return success;
    }
}