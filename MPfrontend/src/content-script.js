(() => {
  const BACKEND_WS_BASE = 'ws://127.0.0.1:8080/ws/music-sync';
  const BACKEND_HTTP_BASE = 'http://127.0.0.1:8080';
  const STORAGE_ROOM_KEY = 'musicParty.roomId';
  const STORAGE_SENDER_KEY = 'musicParty.senderId';
  const STORAGE_IS_HOST_KEY = 'musicParty.isHost';
  const DEBUG = true;

  let ws = null;
  let roomId = getRoomId();
  let senderId = getSenderId();
  let isHost = getIsHost();
  let video = null;
  let lastSentPosition = 0;
  let lastIncomingTimestamp = 0;
  let currentTrack = null;
  let isSyncing = false;
  let allowGuestsAddTracks = false; 
  let isAuthenticated = false; // Estado de autenticación local

  // Evitar inyección doble
  if (window.__musicPartyInjected) return;
  window.__musicPartyInjected = true;

  // Helpers de storage
  function getRoomId() {
    return localStorage.getItem(STORAGE_ROOM_KEY);
  }

  function setRoomId(id) {
    if (id) {
      localStorage.setItem(STORAGE_ROOM_KEY, id);
      roomId = id;
    } else {
      localStorage.removeItem(STORAGE_ROOM_KEY);
      roomId = null;
    }
  }

  function getSenderId() {
    let id = localStorage.getItem(STORAGE_SENDER_KEY);
    if (!id) {
      id = 'user-' + Date.now() + '-' + Math.random().toString(36).substr(2, 9);
      localStorage.setItem(STORAGE_SENDER_KEY, id);
    }
    return id;
  }

  function getIsHost() {
    return localStorage.getItem(STORAGE_IS_HOST_KEY) === 'true';
  }

  function setIsHost(host) {
    localStorage.setItem(STORAGE_IS_HOST_KEY, host.toString());
    isHost = host;
  }

  // Conexión WebSocket mejorada
  function connectSocket(targetRoomId = roomId, targetIsHost = isHost) {
    // Actualizar variables globales para asegurar consistencia si se pasan argumentos
    if (targetRoomId) roomId = targetRoomId;
    if (targetIsHost !== undefined) isHost = targetIsHost;

    if (ws && ws.readyState === WebSocket.OPEN) {
      if (ws.url === BACKEND_WS_BASE && roomId === getRoomId()) {
         if (DEBUG) console.log('Music Party: Already connected to room', roomId);
         // SIEMPRE enviar auth para asegurar que el servidor nos conoce (idempotente)
         sendWebSocketMessage({
            type: 'auth',
            roomId: roomId,
            senderId: senderId,
            data: { isHost: isHost }
         });
         return;
      }
      ws.close();
    }

    if (!roomId) {
      if (DEBUG) console.warn('Music Party: No roomId defined');
      return;
    }

    isAuthenticated = false; // Reset auth flag on new connection
    ws = new WebSocket(BACKEND_WS_BASE);

    ws.onopen = () => {
      if (DEBUG) console.log('Music Party: WebSocket connected');
      
      // Autenticarse con el servidor usando el nuevo formato SyncMsg
      sendWebSocketMessage({
        type: 'auth',
        roomId: roomId,
        senderId: senderId,
        data: { isHost: isHost }
      });
      // NO enviamos estado aquí todavía, esperamos al ACK de auth
    };

    ws.onmessage = (event) => {
      try {
        const message = JSON.parse(event.data);
        handleWebSocketMessage(message);
      } catch (error) {
        console.error('Music Party: Error parsing WebSocket message:', error);
      }
    };

    ws.onclose = (event) => {
      isAuthenticated = false; // Conexión cerrada = No autenticado
      if (DEBUG) console.log('Music Party: WebSocket closed:', event.code, event.reason);
      
      // Códigos de cierre fatales (no reconectar)
      // 1000: Normal, 1001: Going Away, 4xxx: Errores de aplicación
      if (event.code === 1008 || event.code === 4004 || event.code === 4001) {
          console.warn('Music Party: Fatal connection error, stopping reconnection.', event.reason);
          showNotification('❌ Connection refused: ' + (event.reason || 'Unknown error'));
          handleLeaveRoom();
          return;
      }
      
      // Reconectar después de 3 segundos solo si no fue un cierre intencional
      setTimeout(() => connectSocket(), 3000);
    };

    ws.onerror = (error) => {
      isAuthenticated = false;
      console.error('Music Party: WebSocket error:', error);
    };
  }

  function sendWebSocketMessage(message) {
    // Si no estamos conectados, nada que hacer
    if (!ws || ws.readyState !== WebSocket.OPEN) {
        console.warn('Music Party: WebSocket not connected');
        return;
    }

    // Gatekeeper: Solo permitir 'auth' si no estamos autenticados
    if (!isAuthenticated && message.type !== 'auth') {
        if (DEBUG) console.warn('Music Party: Bloqueado mensaje (no autenticado):', message.type);
        return;
    }

    ws.send(JSON.stringify(message));
    if (DEBUG) console.log('Music Party: Sent message:', message);
  }

  // Manejo de mensajes WebSocket unificado
  function handleWebSocketMessage(message) {
    if (!message || !message.type) return;

    // Ignorar mensajes propios (excepto ACKs)
    if (message.senderId === senderId && message.type !== 'ack') return;

    switch (message.type) {
      case 'system':
        handleSystemMessage(message);
        break;
      case 'full_state':
        handleFullStateMessage(message);
        break;
      case 'playback':
        handlePlaybackMessage(message);
        break;
      case 'ack':
        handleAckMessage(message);
        break;
      default:
        if (DEBUG) console.log('Music Party: Unknown message type:', message.type);
    }
  }

  function handleSystemMessage(message) {
    // Soporte para estructura plana o anidada (SyncMsg)
    const data = message.data || {};
    const event = message.event || message.subType || data.event || data.subType;
    const payload = message.payload || data.payload || data; // Si no hay payload explicito, usar data
    
    if (!event) return;
    
    switch (event) {
      case 'user_joined':
        showNotification(`🎵 ${payload.userId} joined the room`);
        break;
      case 'user_left':
        showNotification(`👋 ${payload.userId} left the room`);
        break;
      case 'host_connected':
        showNotification(`👑 Host ${payload.hostId} connected`);
        break;
      case 'trackAdded':
        showNotification(`➕ ${payload.addedBy} added a track`);
        break;
      case 'room_settings_updated':
        updateUIWithSettings(payload);
        break;
    }
  }

  function handlePlaybackMessage(message) {
    if (isSyncing) return; // Evitar bucles de sincronización
    
    const data = message.data || message; // Usar data si existe, o el mensaje mismo
    const state = data.state || message.state; // Intentar ambos niveles
    const currentTrack = data.currentTrack || message.currentTrack;
    const positionMs = data.positionMs !== undefined ? data.positionMs : message.positionMs;
    
    isSyncing = true;
    
    switch (state) {
      case 'play':
        syncPlayback(currentTrack, positionMs);
        break;
      case 'pause':
        if (video && !video.paused) {
          video.pause();
        }
        if (positionMs && video) {
          video.currentTime = positionMs / 1000;
        }
        break;
    }
    
    // Reset sync flag después de un tiempo
    setTimeout(() => { isSyncing = false; }, 1000);
  }

  function handleAckMessage(message) {
    if (DEBUG) console.log('Music Party: ACK received:', message);
    
    // Extraer datos ya sea del nivel superior o de 'data'
    const data = message.data || {};
    const reason = message.reason || data.reason;
    const success = message.success !== undefined ? message.success : data.success;
    
    if (reason === 'authenticated') {
       if (DEBUG) console.log('Music Party: Authentication successful, enabling playback messages');
       isAuthenticated = true;
       
       // Si somos HOST, ahora es seguro enviar el estado inicial
       if (isHost) {
          const currentState = getCurrentPlayerState();
          if (currentState.track) {
            sendNowPlaying(currentState.track);
          }
       }
       return;
    }

    if (!success) {
      if (reason === 'room_not_active') {
        if (DEBUG) console.log('Music Party: Room not active (waiting for host).');
        showNotification('⏳ Waiting for host to start activity...');
        // No reconectar automáticamente para evitar spam. El usuario debe reintentar manualmente o esperar evento.
        // Opcional: Podríamos hacer un long-polling o reintentar más lento (ej. 10s), pero por ahora detenemos el bucle rápido.
        return;
      }
      
      if (reason === 'room_not_found') {
        if (DEBUG) console.log('Music Party: Room not found (expired), clearing session');
        showNotification('❌ Session expired. Please create a new room.');
        handleLeaveRoom(); // Limpia estado local
        return;
      }

      if (reason === 'invalid_session') {
         if (DEBUG) console.log('Music Party: Invalid session, re-authenticating...');
         sendWebSocketMessage({
            type: 'auth',
            roomId: roomId,
            senderId: senderId,
            data: { isHost: isHost }
         });
         return;
      }
      
      if (reason) {
        showNotification(`❌ ${reason}`);
      }
    }
  }

  function handleFullStateMessage(message) {
      if (DEBUG) console.log('Music Party: Full state received:', message);
      const data = message.data || {};
      
      // Sincronizar configuración de la sala
      if (typeof data.allowGuestsEditQueue !== 'undefined') {
          allowGuestsAddTracks = data.allowGuestsEditQueue;
      }
      
      // Sincronizar estado de reproducción actual si existe
      if (data.currentTrack) {
          // TODO: Implementar lógica para sincronizar track inicial si es necesario
          // Por ahora, solo notificamos
          if (DEBUG) console.log('Music Party: Initial track:', data.currentTrack);
      }
      
      showNotification('✅ Connected and synced with room!');
  }

  // Sincronización mejorada de reproducción
  function syncPlayback(track, positionMs) {
    const currentTrack = getCurrentTrackInfo();
    
    // Si es una canción diferente, navegar primero
    if (track && currentTrack.id !== track.trackId) {
      navigateToTrack(track);
    }
    
    // Reproducir y buscar posición
    if (video) {
      const targetTime = positionMs / 1000;
      const timeDifference = Math.abs(video.currentTime - targetTime);
      
      // Solo buscar si la diferencia es significativa
      if (timeDifference > 2) {
        video.currentTime = targetTime;
      }
      
      if (video.paused) {
        video.play().catch(error => {
          console.warn('Music Party: Play failed:', error);
        });
      }
    }
  }

  function navigateToTrack(track) {
    if (track.url && track.url !== window.location.href) {
      if (DEBUG) console.log('Music Party: Navigating to track:', track.url);
      window.location.href = track.url;
    }
  }

  // Detección de estado del reproductor mejorada
  function getCurrentPlayerState() {
    const track = getCurrentTrackInfo();
    const isPlaying = video ? !video.paused : false;
    const currentTime = video ? video.currentTime * 1000 : 0;
    
    return {
      track,
      isPlaying,
      currentTime,
      timestamp: Date.now()
    };
  }

  function getCurrentTrackInfo() {
    // 1. Intentar obtener metadata oficial de MediaSession (Más confiable)
    if ('mediaSession' in navigator && navigator.mediaSession.metadata) {
      const meta = navigator.mediaSession.metadata;
      
      // Intentar obtener la mejor imagen (la más grande)
      let artwork = null;
      if (meta.artwork && meta.artwork.length > 0) {
          artwork = meta.artwork[meta.artwork.length - 1].src;
      }

      // Obtener Video ID de la URL
      let videoId = null;
      try {
        const urlObj = new URL(window.location.href);
        videoId = urlObj.searchParams.get('v');
        // Si no hay param 'v', verificar si es path (ej: /watch/VIDEO_ID)
        if (!videoId && urlObj.pathname.startsWith('/watch')) {
            videoId = urlObj.pathname.split('/').pop();
        }
      } catch (e) {
        if (DEBUG) console.warn('Error parsing URL for ID:', e);
      }
      
      // Fallback si la URL no tiene ID estándar (rara vez pasa en YTM)
      if (!videoId) videoId = window.location.href;

      return {
        trackId: videoId,
        title: meta.title,
        artist: meta.artist,
        albumArt: artwork,
        url: window.location.href
      };
    }

    // 2. Fallback a scraping del DOM (Método antiguo)
    const titleElement = document.querySelector('.title.style-scope.ytmusic-player-bar');
    const artistElement = document.querySelector('.byline.style-scope.ytmusic-player-bar complex-string');
    const albumArtElement = document.querySelector('#thumbnail img');
    
    let title = titleElement ? titleElement.textContent.trim() : null;
    let artist = artistElement ? artistElement.textContent.trim() : null;
    let url = window.location.href;
    
    // Extraer ID del video de la URL
    let videoId = null;
    try {
      const urlObj = new URL(url);
      videoId = urlObj.searchParams.get('v') || urlObj.pathname.split('/').pop();
    } catch (e) {
      videoId = url;
    }
    
    return {
      trackId: videoId,
      title: title,
      artist: artist,
      albumArt: albumArtElement ? albumArtElement.src : null,
      url: url
    };
  }

  function sendNowPlaying(track) {
    // Permitir si es Host O si los invitados tienen permiso
    const canSend = isHost || allowGuestsAddTracks;
    
    if (!canSend) {
        if (DEBUG) console.log('Music Party: Bloqueado envío de nueva canción (Sin permisos)');
        return; 
    }
    
    sendWebSocketMessage({
      type: 'playback',
        subType: 'play',
      roomId: roomId,
      senderId: senderId,
      data: {
        track: track,
        positionMs: 0
      }
    });
  }

  function sendPlaybackState() {
    if (isSyncing) return;
    
    const state = getCurrentPlayerState();
    const action = state.isPlaying ? 'play' : 'pause';
    
    sendWebSocketMessage({
      type: 'playback',
      subType: action,
      roomId: roomId,
      senderId: senderId,
      data: {
        positionMs: Math.floor(state.currentTime)
      }
    });
  }

  // Encontrar y configurar el elemento video
  function setupVideo() {
    video = document.querySelector('video');
    if (!video) {
      // Reintentar después de un tiempo si el video no está disponible
      setTimeout(setupVideo, 1000);
      return;
    }
    
    // Evitar múltiples event listeners
    if (video.__musicPartyListeners) return;
    video.__musicPartyListeners = true;
    
    video.addEventListener('play', () => {
      if (!isSyncing) sendPlaybackState();
    });
    
    video.addEventListener('pause', () => {
      if (!isSyncing) sendPlaybackState();
    });
    
    video.addEventListener('seeked', () => {
      if (!isSyncing) {
        sendWebSocketMessage({
          type: 'playback',
          subType: 'seek',
          roomId: roomId,
          senderId: senderId,
          data: {
            positionMs: Math.floor(video.currentTime * 1000)
          }
        });
      }
    });
    
    // Detectar cambios de URL (navegación)
    let lastUrl = window.location.href;
    const observeUrlChange = () => {
      const currentUrl = window.location.href;
      if (currentUrl !== lastUrl) {
        lastUrl = currentUrl;
        if (isHost || allowGuestsAddTracks) {
          const track = getCurrentTrackInfo();
          sendNowPlaying(track);
        }
      }
      setTimeout(observeUrlChange, 1000);
    };
    observeUrlChange();
  }

  // Observador para cambios en la interfaz de YouTube Music
  function setupMutationObserver() {
    const observer = new MutationObserver((mutations) => {
      mutations.forEach((mutation) => {
        if (mutation.type === 'childList' || mutation.type === 'characterData') {
          const newTrack = getCurrentTrackInfo();
          if (newTrack.trackId && newTrack.trackId !== currentTrack?.trackId) {
            currentTrack = newTrack;
            if (isHost || allowGuestsAddTracks) {
              sendNowPlaying(newTrack);
            }
          }
        }
      });
    });
    
    const playerBar = document.querySelector('ytmusic-player-bar');
    if (playerBar) {
      observer.observe(playerBar, {
        childList: true,
        subtree: true,
        characterData: true
      });
    }
  }

  // Notificaciones visuales
  function showNotification(message) {
    // Crear notificación temporal
    const notification = document.createElement('div');
    notification.style.cssText = `
      position: fixed;
      top: 20px;
      right: 20px;
      background: #333;
      color: white;
      padding: 10px 15px;
      border-radius: 5px;
      z-index: 10000;
      font-family: Arial, sans-serif;
      font-size: 14px;
      box-shadow: 0 2px 10px rgba(0,0,0,0.3);
    `;
    notification.textContent = message;
    document.body.appendChild(notification);
    
    setTimeout(() => {
      if (notification.parentNode) {
        notification.parentNode.removeChild(notification);
      }
    }, 3000);
  }

  function updateUIWithSettings(settings) {
    // Actualizar UI basado en configuraciones de la sala
    if (DEBUG) console.log('Music Party: Room settings updated:', settings);
    
    if (settings && typeof settings.allowGuestsEditQueue !== 'undefined') {
        allowGuestsAddTracks = settings.allowGuestsEditQueue;
        if (DEBUG) console.log('Music Party: allowGuestsAddTracks set to:', allowGuestsAddTracks);
    }
  }

  // Comunicación con el popup/background
  chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
    switch (message.type) {
      case 'joinRoom':
        handleJoinRoom(message.roomId, message.isHost);
        sendResponse({ success: true });
        break;
        
      case 'leaveRoom':
        handleLeaveRoom();
        sendResponse({ success: true });
        break;
        
      case 'getState':
        sendResponse({
          roomId: roomId,
          senderId: senderId,
          isHost: isHost,
          currentTrack: getCurrentTrackInfo()
        });
        break;
        
      case 'controlPlayback':
        handleControlPlayback(message.action, message.position);
        sendResponse({ success: true });
        break;
        
      default:
        sendResponse({ success: false, error: 'Unknown message type' });
    }
    
    return true; // Mantener el mensaje abierto para sendResponse
  });

  function handleJoinRoom(newRoomId, newIsHost) {
    setRoomId(newRoomId);
    setIsHost(newIsHost);
    connectSocket(newRoomId, newIsHost);
    showNotification(newIsHost ? '👑 Created room ' + newRoomId : '🎵 Joined room ' + newRoomId);
  }

  function handleLeaveRoom() {
    if (ws) {
      ws.onclose = null; // Evitar disparar reconexión al cerrar manualmente
      ws.close();
      ws = null;
    }
    setRoomId(null);
    setIsHost(false);
    showNotification('👋 Left the room');
    
    // Notificar al background para limpiar estado global
    try {
      chrome.runtime.sendMessage({ type: 'leaveRoom' });
    } catch (e) {
      // Ignorar si no hay conexión con background
    }
  }

  function handleControlPlayback(action, position) {
    if (!video) return;
    
    switch (action) {
      case 'play':
        video.play();
        break;
      case 'pause':
        video.pause();
        break;
      case 'seek':
        if (position !== undefined) {
          video.currentTime = position / 1000;
        }
        break;
    }
  }

  // Inicialización
  function initialize() {
    setupVideo();
    setupMutationObserver();
    setupMediaListeners(); // Listeners de Play/Pause

    if (isHost) {
        initQueueObserver(); // Sincronización nativa
    }
    
    if (roomId) {
      connectSocket(roomId, isHost);
    }
    
    if (DEBUG) {
      console.log('Music Party: Content script initialized', {
        roomId, senderId, isHost
      });
    }
  }

  // ==================== QUEUE OBSERVER (Native Sync) ====================
  
  let queueObserver = null;

  function initQueueObserver() {
    if (!isHost) return; // Solo el host sincroniza SU cola
    if (queueObserver) return; // Ya iniciado

    console.log('Music Party: Iniciando observación de cola nativa...');
    
    // El contenedor de la cola suele ser <ytmusic-player-queue> o similar
    // Nota: YouTube Music carga esto dinámicamente. Necesitamos un observador global o sondear.
    // Estrategia: Observar cambios en el body y buscar el elemento de cola si no existe.
    
    const checkForQueue = setInterval(() => {
        const queueContainer = document.querySelector('ytmusic-player-queue');
        if (queueContainer) {
            clearInterval(checkForQueue);
            startObservingQueue(queueContainer);
        }
    }, 2000);
  }

  function startObservingQueue(container) {
    console.log('Music Party: Cola encontrada. Observando cambios...');
    
    // Función para extraer y enviar
    const readAndSync = () => {
        const tracks = extractQueueTracks(container);
        if (tracks.length > 0) {
            sendSyncQueue(tracks);
        }
    };

    // Observar cambios en el DOM de la lista
    queueObserver = new MutationObserver((mutations) => {
        // Debounce simple
        if (window._queueSyncTimeout) clearTimeout(window._queueSyncTimeout);
        window._queueSyncTimeout = setTimeout(readAndSync, 1000);
    });

    queueObserver.observe(container, {
        childList: true,
        subtree: true,
        attributes: true, // A veces cambian atributos playing
        characterData: true
    });
    
    // Lectura inicial
    readAndSync();
  }

  function extractQueueTracks(container) {
    // Selector específico para YT Music (puede requerir ajuste si cambia el DOM)
    // Usualmente son <ytmusic-player-queue-item>
    const items = container.querySelectorAll('ytmusic-player-queue-item');
    const tracks = [];

    items.forEach((item, index) => {
        const titleEl = item.querySelector('.song-title');
        const artistEl = item.querySelector('.byline');
        const imgEl = item.querySelector('img');
        const timeEl = item.querySelector('.duration'); 

        if (titleEl) {
            tracks.push({
                trackId: 'native-' + index, // IDs temporales basados en posición
                title: titleEl.innerText,
                addedBy: 'Host (Native)',
                durationMs: timeEl ? parseDuration(timeEl.innerText) : 0,
                // thumbnail: imgEl ? imgEl.src : null // Opcional
            });
        }
    });
    return tracks;
  }
  
  function parseDuration(timeStr) {
     if (!timeStr) return 0;
     const parts = timeStr.split(':');
     if (parts.length === 2) {
         return (parseInt(parts[0]) * 60 + parseInt(parts[1])) * 1000;
     }
     return 0;
  }

  function sendSyncQueue(tracks) {
      if (!isAuthenticated || !roomId || !isHost) return;
      
      sendWebSocketMessage({
          type: 'playlist',
          subType: 'sync_queue', // Nuevo subtipo
          roomId: roomId,
          senderId: senderId,
          data: {
              tracks: tracks,
              source: 'native_queue'
          }
      });
      if (DEBUG) console.log(`Music Party: Enviando cola nativa (${tracks.length} tracks)`);
  }

  // Hook into initialization
  // ... existing initialization code ...
  
  // ==================== EXISTING CODE END ====================
  // Iniciar cuando el DOM esté listo
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', initialize);
  } else {
    initialize();
  }

  // Exponer para debugging
  window.musicParty = {
    getState: () => ({ roomId, senderId, isHost, currentTrack: getCurrentTrackInfo() }),
    reconnect: () => connectSocket(),
    debug: () => ({ ws: ws ? ws.readyState : 'null', roomId, senderId, isHost })
  };
})();