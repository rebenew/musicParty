import { Component, OnInit, OnDestroy, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { Subscription, map, distinctUntilChanged, debounceTime, Subject } from 'rxjs';
import { SyncOrchestratorService } from './services/sync-orchestrator.service';
import { StateService } from './services/state.service';

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
    this.initializeUser();
    this.setupSubscriptions();
    this.setupHostSettingsDebounce();
  }

  ngOnDestroy(): void {
    this.subs.forEach(s => s.unsubscribe());
    this.hostSettingsDebounce.complete();
    this.syncOrchestrator.stop();
  }


  /**
   * Configura todas las suscripciones a observables
   */
  private setupSubscriptions(): void {

    // Manejar errores de sincronización
    this.subs.push(
      this.syncOrchestrator.errors$.subscribe(error => 
        this.setStatus(error, 'error', 5000)
      )
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
   * Inicializa el senderId del usuario
   */
  private initializeUser(): void {
    const senderId = localStorage.getItem('senderId') ?? crypto.randomUUID();
    localStorage.setItem('senderId', senderId);
    this.state.patch({ senderId });
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
      const senderId = this.state.snapshot.senderId;
      if (!senderId) {
        this.setStatus('No se pudo obtener el senderId', 'error');
        return;
      }
      const result = await this.syncOrchestrator.hostCreateAndConnect(senderId);
      
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
      const senderId = this.state.snapshot.senderId;
      if (!senderId) {
        this.setStatus('No se pudo obtener el senderId', 'error');
        return;
      }
      await this.syncOrchestrator.guestJoin(roomId, senderId);
      
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
   * Desconectar de la sala
   */
  disconnect(): void {
    this.syncOrchestrator.stop();
    this.role = null;
    this.guestRoomId = ''; // Limpiar input
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