import { Injectable } from '@angular/core';
import { Subject, Observable } from 'rxjs';
import { StateService } from './state.service';

/**
 * Servicio especializado en procesamiento y routing de mensajes
 * Convierte mensajes brutos en acciones sobre el estado y eventos
 */
@Injectable({ providedIn: 'root' })
export class MessageHandlerService {
  private systemMessages$ = new Subject<any>();
  private playbackMessages$ = new Subject<any>();
  private playlistMessages$ = new Subject<any>();

  constructor(private state: StateService) {}

  /**
   * Procesa un mensaje entrante y lo dirige al handler correspondiente
   */
  handleMessage(msg: any): void {
    if (!msg) return;

    // Manejar ACK (consumidos internamente, no se emiten)
    if (this.isAckMessage(msg)) {
      return; // Los ACK se manejan en los servicios que los esperan
    }

    // Routing por tipo de mensaje
    switch (msg.type) {
      case 'system':
        this.handleSystemMessage(msg);
        break;
      case 'playback':
        this.handlePlaybackMessage(msg);
        break;
      case 'playlist':
        this.handlePlaylistMessage(msg);
        break;
      default:
        console.warn('Tipo de mensaje desconocido:', msg.type);
    }
  }

  /**
   * Maneja mensajes del sistema (usuarios, configuración, etc.)
   */
  private handleSystemMessage(msg: any): void {
    const subType = msg.subType;
    const payload = msg.data || msg.payload;

    switch (subType) {
      case 'navigateTo':
        this.systemMessages$.next({ type: 'navigate', url: payload?.url || payload });
        break;
      
      case 'room_closed_by_host':
        this.systemMessages$.next({ type: 'roomClosed' });
        break;
      
      case 'user_joined':
      case 'user_left':
        this.handleUserEvent(subType, payload);
        this.systemMessages$.next({ type: 'userUpdate', subType, user: payload });
        break;
      
      case 'trackAdded':
        this.systemMessages$.next({ type: 'trackAdded', track: payload });
        break;
      
      case 'playlist_updated':
        this.systemMessages$.next({ type: 'playlistUpdate' });
        break;
      
      case 'playback_state_changed':
        this.handlePlaybackStateChange(payload);
        break;
      
      case 'settings_updated':
        this.handleSettingsUpdated(payload);
        this.systemMessages$.next({ type: 'settingsUpdate', settings: payload });
        break;
      
      default:
        this.systemMessages$.next(msg);
    }
  }

  /**
   * Maneja mensajes de control de reproducción
   */
  private handlePlaybackMessage(msg: any): void {
    // Actualizar estado de reproducción
    if (msg.action) {
      const isPlaying = msg.action === 'play' || msg.action === 'play';
      this.state.updatePlaybackState(isPlaying, msg.position);
    }

    // Emitir para componentes interesados
    this.playbackMessages$.next(msg);
  }

  /**
   * Maneja mensajes de gestión de playlist
   */
  private handlePlaylistMessage(msg: any): void {
    // Emitir para componentes interesados
    this.playlistMessages$.next(msg);

    // Trigger refresh si es necesario
    if (msg.action === 'addTrack' || msg.action === 'removeTrack') {
      this.systemMessages$.next({ type: 'playlistUpdate' });
    }
  }

  /**
   * Maneja eventos de usuarios (conexión/desconexión)
   */
  private handleUserEvent(subType: string, payload: any): void {
    const userId = payload?.userId || payload?.senderId;
    
    if (!userId) {
      console.warn('Evento de usuario sin ID:', { subType, payload });
      return;
    }

    if (subType === 'user_joined') {
      this.state.addUser(userId);
    } else if (subType === 'user_left') {
      this.state.removeUser(userId);
    }
  }

  /**
   * Maneja cambios en el estado de reproducción
   */
  private handlePlaybackStateChange(payload: any): void {
    if (payload && typeof payload.isPlaying === 'boolean') {
      this.state.updatePlaybackState(payload.isPlaying, payload.position);
    }
  }

  /**
   * Maneja actualización de configuraciones
   */
  private handleSettingsUpdated(payload: any): void {
    if (payload) {
      this.state.patch({
        allowGuestsControl: payload.allowGuestsControl ?? this.state.snapshot.allowGuestsControl,
        allowGuestsAddTracks: payload.allowGuestsAddTracks ?? this.state.snapshot.allowGuestsAddTracks
      });
    }
  }

  /**
   * Determina si un mensaje es un ACK
   */
  private isAckMessage(msg: any): boolean {
    return msg.type === 'ack' || 
           (msg.correlationId && msg.hasOwnProperty('success'));
  }

  // Observables públicos para diferentes tipos de mensajes
  get systemMessages(): Observable<any> { return this.systemMessages$.asObservable(); }
  get playbackMessages(): Observable<any> { return this.playbackMessages$.asObservable(); }
  get playlistMessages(): Observable<any> { return this.playlistMessages$.asObservable(); }
}