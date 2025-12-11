package com.rebenew.musicParty.syncserver.service;

import com.rebenew.musicParty.syncserver.core.RoomSessionManager;
import com.rebenew.musicParty.syncserver.model.RoomSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PlaybackService {
    private static final Logger logger = LoggerFactory.getLogger(PlaybackService.class);

    private final RoomSessionManager roomSessionManager;

    public PlaybackService(RoomSessionManager roomSessionManager) {
        this.roomSessionManager = roomSessionManager;
    }

    public boolean play(String roomId, String userId, Integer trackIndex, Long positionMs) {
        RoomSession session = roomSessionManager.getSession(roomId);
        if (session == null) {
            logger.warn("Attempt to play in non-existent room: {}", roomId);
            return false;
        }

        if (!session.canUserControlPlayback(userId)) {
            logger.warn("User {} has no permission to play in room: {}", userId, roomId);
            return false;
        }

        boolean success = session.play(userId, trackIndex, positionMs);
        if (success) {
            long actualPosition = positionMs != null ? positionMs : session.getCurrentPlaybackPosition();
            roomSessionManager.broadcastPlaybackState(session, "play", actualPosition);
            logger.info("▶️ Playback started in room {} by {} (track: {}, position: {}ms)",
                    roomId, userId, session.getNowPlayingIndex(), actualPosition);
        }
        return success;
    }

    public boolean pause(String roomId, String userId) {
        RoomSession session = roomSessionManager.getSession(roomId);
        if (session == null) {
            logger.warn("Attempt to pause in non-existent room: {}", roomId);
            return false;
        }

        if (!session.canUserControlPlayback(userId)) {
            logger.warn("User {} has no permission to pause in room: {}", userId, roomId);
            return false;
        }

        boolean success = session.pause(userId);
        if (success) {
            long currentPosition = session.getCurrentPlaybackPosition();
            roomSessionManager.broadcastPlaybackState(session, "pause", currentPosition);
            logger.info("⏸️ Playback paused in room {} by {} (position: {}ms)",
                    roomId, userId, currentPosition);
        }
        return success;
    }

    public boolean nextTrack(String roomId, String userId) {
        RoomSession session = roomSessionManager.getSession(roomId);
        if (session == null) {
            logger.warn("Attempt to skip to next track in non-existent room: {}", roomId);
            return false;
        }

        if (!session.canUserControlPlayback(userId)) {
            logger.warn("User {} has no permission to skip to next track in room: {}", userId, roomId);
            return false;
        }

        boolean success = session.nextTrack(userId);
        if (success) {
            roomSessionManager.broadcastPlaybackState(session, "play", 0L);
            logger.info("⏭️ Skipped to next track in room {} by {} (new track: {})",
                    roomId, userId, session.getNowPlayingIndex());
        }
        return success;
    }

    public boolean previousTrack(String roomId, String userId) {
        RoomSession session = roomSessionManager.getSession(roomId);
        if (session == null) {
            logger.warn("Attempt to skip to previous track in non-existent room: {}", roomId);
            return false;
        }

        if (!session.canUserControlPlayback(userId)) {
            logger.warn("User {} has no permission to skip to previous track in room: {}", userId, roomId);
            return false;
        }

        boolean success = session.previousTrack(userId);
        if (success) {
            roomSessionManager.broadcastPlaybackState(session, "play", 0L);
            logger.info("⏮️ Skipped to previous track in room {} by {} (new track: {})",
                    roomId, userId, session.getNowPlayingIndex());
        }
        return success;
    }

    public boolean seek(String roomId, String userId, long positionMs) {
        RoomSession session = roomSessionManager.getSession(roomId);
        if (session == null) {
            logger.warn("Attempt to seek in non-existent room: {}", roomId);
            return false;
        }

        if (!session.canUserControlPlayback(userId)) {
            logger.warn("User {} has no permission to seek in room: {}", userId, roomId);
            return false;
        }

        boolean success = session.seek(userId, positionMs);
        if (success) {
            roomSessionManager.broadcastPlaybackState(session, "seek", positionMs);
            logger.info("🔍 Seek in room {} by {} to {}ms", roomId, userId, positionMs);
        }
        return success;
    }
}