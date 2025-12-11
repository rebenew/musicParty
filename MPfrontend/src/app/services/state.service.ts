import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { map, distinctUntilChanged } from 'rxjs/operators';

// ✅ IMPORTADO: Usar el tipo unificado de RoomService
import { TrackEntry } from './room.service';

export interface AppState {
  roomId: string | null;
  senderId: string | null;
  isHost: boolean;
  isConnected: boolean;
  allowGuestsAddTracks: boolean;
  allowGuestsControl: boolean; 
  playlist: TrackEntry[];
  users: string[]; // Lista de usuarios conectados
  currentTrack?: { // Track actual reproduciéndose
    trackId: string;
    title: string;
    artist?: string;
    position?: number;
    isPlaying: boolean;
  };
}

/*
 * Servicio de estado global para la aplicación.
 * Maneja el estado reactivo y proporciona observables para componentes.
 */

@Injectable({ providedIn: 'root' })
export class StateService {
  private initial: AppState = {
    roomId: null,
    senderId: null,
    isHost: false,
    isConnected: false,
    allowGuestsAddTracks: false,
    allowGuestsControl: false,
    playlist: [],
    users: []
  };

  private stateSubject = new BehaviorSubject<AppState>(this.initial);
  
  // Observable público del estado completo
  public state$: Observable<AppState> = this.stateSubject.asObservable();

  public roomId$: Observable<string | null> = this.state$.pipe(
    map(s => s.roomId),
    distinctUntilChanged()
  );

  public isConnected$: Observable<boolean> = this.state$.pipe(
    map(s => s.isConnected),
    distinctUntilChanged()
  );

  public playlist$: Observable<TrackEntry[]> = this.state$.pipe(
    map(s => s.playlist),
    distinctUntilChanged()
  );

  public users$: Observable<string[]> = this.state$.pipe(
    map(s => s.users),
    distinctUntilChanged()
  );

  public currentTrack$: Observable<AppState['currentTrack']> = this.state$.pipe(
    map(s => s.currentTrack),
    distinctUntilChanged()
  );

  //Obtiene snapshot actual del estado
  get snapshot(): AppState {
    return this.stateSubject.value;
  }

  // Actualiza parcialmente el estado
  
  patch(updates: Partial<AppState>): void {
    const currentState = this.snapshot;
    const newState = { ...currentState, ...updates };
    this.stateSubject.next(newState);
  }

  // Restablece el estado al inicial
  reset(): void {
    this.stateSubject.next(this.initial);
  }

  // Métodos específicos para playlist

  setPlaylist(playlist: TrackEntry[]): void {
    this.patch({ 
      playlist: Array.isArray(playlist) ? playlist : [] 
    });
  }

  clearPlaylist(): void {
    this.patch({ playlist: [] });
  }

  appendToPlaylist(entry: TrackEntry): void {
    const currentPlaylist = this.snapshot.playlist;
    const newPlaylist = [...currentPlaylist, {
      ...entry,
      addedAt: entry.addedAt || Date.now() 
    }];
    this.setPlaylist(newPlaylist);
  }

  removeFromPlaylist(trackId: string): void {
    const currentPlaylist = this.snapshot.playlist;
    const newPlaylist = currentPlaylist.filter(track => track.trackId !== trackId);
    this.setPlaylist(newPlaylist);
  }

  // Métodos para usuarios

  setUsers(users: string[]): void {
    this.patch({ users: Array.isArray(users) ? users : [] });
  }

  addUser(userId: string): void {
    const currentUsers = this.snapshot.users;
    if (!currentUsers.includes(userId)) {
      this.setUsers([...currentUsers, userId]);
    }
  }

  removeUser(userId: string): void {
    const currentUsers = this.snapshot.users;
    this.setUsers(currentUsers.filter(user => user !== userId));
  }

  //Métodos para track actual

  setCurrentTrack(track: AppState['currentTrack']): void {
    this.patch({ currentTrack: track });
  }

  updatePlaybackState(isPlaying: boolean, position?: number): void {
    const currentTrack = this.snapshot.currentTrack;
    if (currentTrack) {
      this.setCurrentTrack({
        ...currentTrack,
        isPlaying,
        position: position ?? currentTrack.position
      });
    }
  }

  // Métodos de utilidad

  isUserInRoom(): boolean {
    return !!this.snapshot.roomId && this.snapshot.isConnected;
  }

  canUserAddTracks(): boolean {
    return this.isUserInRoom() && (
      this.snapshot.isHost || this.snapshot.allowGuestsAddTracks
    );
  }

  canUserControlPlayback(): boolean {
    return this.isUserInRoom() && (
      this.snapshot.isHost || this.snapshot.allowGuestsControl
    );
  }

  getRoomInfo() {
    const state = this.snapshot;
    return {
      roomId: state.roomId,
      isHost: state.isHost,
      userCount: state.users.length,
      playlistSize: state.playlist.length,
      permissions: {
        canAddTracks: this.canUserAddTracks(),
        canControlPlayback: this.canUserControlPlayback()
      }
    };
  }
}