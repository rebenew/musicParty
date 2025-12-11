package com.rebenew.musicParty.syncserver.model;

import java.util.List;
import java.util.Map;

public class PlaylistResponse {
    private String roomId;
    private List<Map<String, Object>> playlist;
    private int totalTracks;
    private Integer nowPlayingIndex;
    private TrackEntry nowPlaying;

    public PlaylistResponse(String roomId, List<Map<String, Object>> playlist, int totalTracks, Integer nowPlayingIndex, TrackEntry nowPlaying) {
        this.roomId = roomId;
        this.playlist = playlist;
        this.totalTracks = totalTracks;
        this.nowPlayingIndex = nowPlayingIndex;
        this.nowPlaying = nowPlaying;
    }

    public String getRoomId() {
        return roomId;
    }

    public void setRoomId(String roomId) {
        this.roomId = roomId;
    }

    public List<Map<String, Object>> getPlaylist() {
        return playlist;
    }

    public void setPlaylist(List<Map<String, Object>> playlist) {
        this.playlist = playlist;
    }

    public int getTotalTracks() {
        return totalTracks;
    }

    public void setTotalTracks(int totalTracks) {
        this.totalTracks = totalTracks;
    }

    public Integer getNowPlayingIndex() {
        return nowPlayingIndex;
    }

    public void setNowPlayingIndex(Integer nowPlayingIndex) {
        this.nowPlayingIndex = nowPlayingIndex;
    }

    public TrackEntry getNowPlaying() {
        return nowPlaying;
    }

    public void setNowPlaying(TrackEntry nowPlaying) {
        this.nowPlaying = nowPlaying;
    }
}