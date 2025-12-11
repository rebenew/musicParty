import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { Subscription, map, distinctUntilChanged, debounceTime, Subject } from 'rxjs';
import { SyncOrchestratorService } from './services/sync-orchestrator.service';
import { StateService } from './services/state.service';
import { MessageHandlerService } from './services/message-handler.service';
import { StorageService } from './services/storage-service';
import { UserService } from './services/user-service';

/**
 * Componente principal de la aplicación/extensión
 * Maneja la UI de sincronización musical usando solo roomId
 */
@Component({
  selector: 'app-root',
  standalone: true,
  imports: [FormsModule, CommonModule],
  templateUrl: './app.component.html',
  styleUrls: ['./app.component.css']
})
export class AppComponent implements OnInit, OnDestroy {
  // ✅ SERVICIOS INYECTADOS
  private syncOrchestrator = inject(SyncOrchestratorService);
  private state = inject(StateService);
  private messageHandler = inject(MessageHandlerService);
  private storage = inject(StorageService);
  private userService = inject(UserService);
  
  private subs: Subscription[] = [];
  private hostSettingsDebounce = new Subject<{control: boolean, edit: boolean}>();

  // ✅ ESTADO REACTIVO
  isConnected$ = this.state.isConnected$;
  roomId$ = this.state.roomId$;
  playlist$ = this.state.playlist$;
  currentTrack$ = this.state.currentTrack$;
  
  // ✅ ESTADO LOCAL DE UI
  role: 'host' | 'guest' | null = null;
  creatingRoom = false;
  guestRoomId = '';
  lastError?: string;

  // ✅ OBSERVABLES COMPUESTOS
  canControlPlayback$ = this.state.state$.pipe(
    map(s => s.isHost || s.allowGuestsControl),
    distinctUntilChanged()
  );

  canAddTracks$ = this.state.state$.pipe(
    map(s => s.isHost || s.allowGuestsAddTracks),
    distinctUntilChanged()
  );

  ngOnInit(): void {
    this.hydrateFromStorage();
    this.syncWithBackground();
    this.setupSubscriptions();
    this.setupHostSettingsDebounce();
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
    this.hostSettingsDebounce.complete();
    this.syncOrchestrator.stop();
  }

  /**
   * Recupera estado persistido del storage
   */
  private hydrateFromStorage(): void {
    this.role = this.storage.get<'host' | 'guest'>('role');
    
    // Auto-conectar si hay roomId persistido
    const savedRoomId = this.storage.get<string>('roomId');
    const senderId = this.userService.getSenderId();
    
    if (savedRoomId && senderId && this.role) {
      this.attemptAutoConnect(savedRoomId, senderId);
    }
  }

  /**
   * Sincroniza con el script de fondo para obtener el estado real
   */
  private syncWithBackground(): void {
    if (chrome && chrome.runtime) {
      chrome.runtime.sendMessage({ type: 'getRoomState' }, (response) => {
        if (response && response.success && response.room) {
          console.log('Sincronizado con background:', response.room);
          this.storage.set('roomId', response.room.roomId);
          // Si el background dice que somos host (o estamos en sala y somos owner), actualizar
          // Nota: getRoomState devuelve datos de la sala del servidor.
          // Mejor pedir 'getState' al background que devuelve {roomId, senderId, isHost} local
        }
      });

      // Pedir estado local de sesión al background
      chrome.runtime.sendMessage({ type: 'getSessionState' }, (session) => {
         if (session && session.roomId) {
            console.log('Sesión restaurada de background:', session);
            this.storage.set('roomId', session.roomId);
            this.storage.set('role', session.isHost ? 'host' : 'guest');
            this.role = session.isHost ? 'host' : 'guest';
            
            // Si no estamos conectados en UI pero background sí, reconectar orquestador
            if (!this.state.snapshot.isConnected) {
               this.syncOrchestrator.start(session.roomId, session.senderId);
            }
         }
      });
    }
  }

  /**
   * Configura todas las suscripciones a observables
   */
  private setupSubscriptions(): void {
    // Manejar mensajes del sistema
    this.subs.push(
      this.messageHandler.systemMessages.subscribe(msg => 
        this.handleSystemMessage(msg)
      )
    );

    // Manejar errores de sincronización
    this.subs.push(
      this.syncOrchestrator.errors$.subscribe(error => 
        this.setStatus(error, 'error', 5000)
      )
    );

    // Sincronizar estado de rol con StateService
    this.subs.push(
      this.state.state$.pipe(
        map(s => s.isHost ? 'host' : (s.roomId ? 'guest' : null)),
        distinctUntilChanged()
      ).subscribe(role => {
        this.role = role;
        this.storage.set('role', role);
      })
    );

    // Sincronizar permisos con storage
    this.subs.push(
      this.state.state$.pipe(
        map(s => ({ 
          allowControl: s.allowGuestsControl,
          allowEdit: s.allowGuestsAddTracks 
        })),
        distinctUntilChanged()
      ).subscribe(settings => {
        this.storage.set('allowControl', settings.allowControl);
        this.storage.set('allowEdit', settings.allowEdit);
      })
    );
  }

  /**
   * Configura debounce para ajustes del host
   */
  private setupHostSettingsDebounce(): void {
    this.subs.push(
      this.hostSettingsDebounce.pipe(
        debounceTime(350)
      ).subscribe(settings => {
        this.syncOrchestrator.sendHostSettings(settings.control, settings.edit);
      })
    );
  }

  /**
   * Intenta conexión automática al cargar
   */
  private async attemptAutoConnect(roomId: string, senderId: string): Promise<void> {
    try {
      if (this.role === 'host') {
        // CAMBIO: Usar rejoin en lugar de create
        await this.syncOrchestrator.hostRejoin(roomId, senderId);
      } else {
        await this.syncOrchestrator.guestJoin(roomId, senderId);
      }
    } catch (error) {
      console.warn('Conexión automática fallida:', error);
      // Si falla rejoin (sala no existe), limpiar para que el usuario pueda crear otra
      if (this.role === 'host') {
         this.disconnect(); 
      }
    }
  }

  // ✅ MÉTODOS PÚBLICOS

  /**
   * Crear nueva sala como host
   */
  async createRoom(): Promise<void> {
    if (this.creatingRoom) return;
    
    this.creatingRoom = true;
    this.lastError = undefined;

    try {
      const senderId = this.userService.getSenderId();
      const result = await this.syncOrchestrator.hostCreateAndConnect(senderId);
      
      this.storage.set('roomId', result.roomId);
      this.setRole('host');

      await this.copyToClipboard(result.roomId, 'Room ID');
      
    } catch (error: any) {
      this.setStatus(error?.message || 'Error creando sala', 'error');
    } finally {
      this.creatingRoom = false;
    }
  }

  /**
   * Unirse a sala usando roomId directamente
   */
  async joinRoom(roomIdInput?: string): Promise<void> {
    const roomId = (roomIdInput || this.guestRoomId || '').trim();
    
    if (!roomId) {
      this.setStatus('Ingresa un Room ID válido', 'error');
      return;
    }

    this.lastError = undefined;

    try {
      const senderId = this.userService.getSenderId();
      await this.syncOrchestrator.guestJoin(roomId, senderId);
      
      this.storage.set('roomId', roomId);
      this.setRole('guest');
      this.setStatus(`Unido a sala: ${roomId}`, 'info', 2500);
      
    } catch (error: any) {
      this.setStatus(error?.message || 'No se pudo unir a la sala', 'error');
    }
  }

  /**
   * Establecer rol del usuario
   */
  setRole(role: 'host' | 'guest' | null): void {
    this.role = role;
    this.storage.set('role', role);
    this.lastError = undefined;
  }

  /**
   * Alternar reproducción/pausa
   */
  togglePlay(): void {
    const currentTrack = this.state.snapshot.currentTrack;
    const action = currentTrack?.isPlaying ? 'pause' : 'play';
    
    this.syncOrchestrator.sendAction(action).catch(error => {
      console.warn('Error enviando acción:', error);
    });
  }

  /**
   * Buscar a posición específica
   */
  async sendSeek(position?: number): Promise<void> {
    if (position === undefined) {
      position = this.promptForSeekPosition();
      if (position === undefined) return;
    }

    await this.syncOrchestrator.sendAction('seek', position);
  }

  /**
   * Solicitar posición de búsqueda al usuario
   */
  private promptForSeekPosition(): number | undefined {
    const input = prompt('Posición en segundos', '0');
    if (input === null) return undefined;
    
    const position = Number(input);
    return Number.isFinite(position) ? position : undefined;
  }

  /**
   * Añadir track a la playlist
   */
  async onAddTrack(trackId: string, title: string): Promise<void> {
    if (!trackId?.trim() || !title?.trim()) {
      this.setStatus('ID y título del track son requeridos', 'error');
      return;
    }

    try {
      const success = await this.syncOrchestrator.sendAddTrack(
        trackId.trim(), 
        title.trim()
      );

      if (!success) {
        throw new Error('No se pudo añadir el track');
      }

      this.setStatus('Track añadido correctamente', 'info', 2000);
      
    } catch (error: any) {
      this.setStatus(error.message || 'Error añadiendo track', 'error');
    }
  }

  /**
   * Actualizar configuración de permisos - Control de reproducción
   */
  onToggleAllowGuestsControl(event: Event): void {
    const input = event.target as HTMLInputElement;
    const allowControl = input.checked;
    const currentAllowEdit = this.state.snapshot.allowGuestsAddTracks;
    
    this.updateHostSettings(allowControl, currentAllowEdit);
  }

  /**
   * Actualizar configuración de permisos - Añadir tracks
   */
  onToggleAllowGuestsEdit(event: Event): void {
    const input = event.target as HTMLInputElement;
    const allowEdit = input.checked;
    const currentAllowControl = this.state.snapshot.allowGuestsControl;
    
    this.updateHostSettings(currentAllowControl, allowEdit);
  }

  /**
   * Actualizar configuración de permisos con debounce
   */
  private updateHostSettings(allowControl: boolean, allowEdit: boolean): void {
    if (this.role !== 'host') return;

    this.hostSettingsDebounce.next({
      control: allowControl,
      edit: allowEdit
    });
  }

  /**
   * Manejar mensajes del sistema
   */
  private handleSystemMessage(msg: any): void {
    switch (msg.type) {
      case 'roomClosed':
        this.handleRoomClosed();
        break;
      case 'navigate':
        window.open(msg.url, '_blank');
        break;
      case 'userUpdate':
        console.log(`Usuario ${msg.subType}:`, msg.user);
        break;
    }
  }

  /**
   * Manejar cierre de sala por host
   */
  private handleRoomClosed(): void {
    this.setStatus('La sala fue cerrada por el host', 'error', 5000);
    this.disconnect();
  }

  /**
   * Desconectar de la sala
   */
  disconnect(): void {
    this.syncOrchestrator.stop();
    this.storage.remove('roomId');
    this.role = null;
    this.guestRoomId = ''; // Limpiar input
  }

  /**
   * Reconectar a la sala
   */
  async reconnect(): Promise<void> {
    const roomId = this.storage.get<string>('roomId');
    const senderId = this.userService.getSenderId();

    if (!roomId) {
      this.setStatus('No hay sala para reconectar', 'error');
      return;
    }

    try {
      await this.syncOrchestrator.start(roomId, senderId);
    } catch (error: any) {
      this.setStatus(error.message || 'Error reconectando', 'error');
    }
  }

  /**
   * Copiar roomId al portapapeles
   */
  async copyRoomId(): Promise<void> {
    const roomId = this.storage.get<string>('roomId');
    
    if (!roomId) {
      this.setStatus('No hay Room ID para copiar', 'error');
      return;
    }

    await this.copyToClipboard(roomId, 'Room ID');
  }

  /**
   * Actualizar playlist manualmente
   */
  refreshPlaylist(): void {
    this.syncOrchestrator.refreshPlaylist()
      .catch(err => console.warn('Error actualizando playlist:', err));
  }

  // ✅ MÉTODOS DE UTILIDAD

  /**
   * Copiar texto al portapapeles
   */
  private async copyToClipboard(text: string, label: string): Promise<void> {
    try {
      await navigator.clipboard.writeText(text);
      this.setStatus(`${label} copiado: ${text}`, 'info', 2000);
    } catch {
      this.setStatus(`${label}: ${text}`, 'info', 3000);
    }
  }

  /**
   * Mostrar mensaje de estado
   */
  private setStatus(text: string, kind: 'info' | 'error' = 'info', ttl = 3000): void {
    this.lastError = kind === 'error' ? text : undefined;
    
    if (ttl > 0) {
      setTimeout(() => {
        if (this.lastError === text) {
          this.lastError = undefined;
        }
      }, ttl);
    }
  }
}