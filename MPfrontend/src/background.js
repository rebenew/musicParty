class BackgroundService {
  constructor() {
    this.roomId = null;
    this.senderId = null;
    this.isHost = false;
    this.setupMessageHandlers();
    this.restoreSession();
  }

  setupMessageHandlers() {
    chrome.runtime.onMessage.addListener((request, sender, sendResponse) => {
      this.handleMessage(request, sender, sendResponse);
      return true; // Mantener el mensaje abierto para sendResponse asíncrono
    });

    // Manejar cambios de pestañas
    chrome.tabs.onUpdated.addListener((tabId, changeInfo, tab) => {
      if (changeInfo.status === 'complete' && tab.url?.includes('music.youtube.com')) {
        // Reenviar estado actual a la nueva pestaña
        this.sendToContentScript(tabId, {
          type: 'sessionUpdate',
          roomId: this.roomId,
          senderId: this.senderId,
          isHost: this.isHost
        });
      }
    });
  }

  async handleMessage(request, sender, sendResponse) {
    try {
      switch (request.type) {
        case 'createRoom':
          await this.handleCreateRoom(request, sendResponse);
          break;
          
        case 'joinRoom':
          await this.handleJoinRoom(request, sendResponse);
          break;
          
        case 'leaveRoom':
          await this.handleLeaveRoom(sendResponse);
          break;
          
        case 'getSessionState':
          this.handleGetSessionState(sendResponse);
          break;
          
        case 'getRoomState':
          await this.handleGetRoomState(request, sendResponse);
          break;
          
        case 'controlPlayback':
          await this.handleControlPlayback(request, sendResponse);
          break;
          
        default:
          sendResponse({ success: false, error: 'Unknown message type' });
      }
    } catch (error) {
      console.error('Background service error:', error);
      sendResponse({ success: false, error: error.message });
    }
  }

  async handleCreateRoom(request, sendResponse) {
    try {
      const response = await fetch('http://127.0.0.1:8080/rooms/create', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ senderId: request.senderId })
      });
      
      if (!response.ok) throw new Error('Failed to create room');
      
      const data = await response.json();
      this.roomId = data.roomId;
      this.senderId = request.senderId;
      this.isHost = true;
      
      await this.saveSession();
      
      // Notificar a todas las pestañas de YouTube Music
      this.broadcastToAllTabs({
        type: 'joinRoom',
        roomId: this.roomId,
        isHost: true
      });
      
      sendResponse({ success: true, roomId: this.roomId });
      
    } catch (error) {
      console.error('Create room error:', error);
      sendResponse({ success: false, error: error.message });
    }
  }

  async handleJoinRoom(request, sendResponse) {
    try {
      // Verificar que la sala existe
      const response = await fetch(`http://127.0.0.1:8080/rooms/${request.roomId}`);
      if (!response.ok) throw new Error('Room not found');
      
      this.roomId = request.roomId;
      this.senderId = request.senderId;
      this.isHost = false;
      
      await this.saveSession();
      
      // Notificar a todas las pestañas de YouTube Music
      this.broadcastToAllTabs({
        type: 'joinRoom',
        roomId: this.roomId,
        isHost: false
      });
      
      sendResponse({ success: true });
      
    } catch (error) {
      console.error('Join room error:', error);
      sendResponse({ success: false, error: error.message });
    }
  }

  async handleLeaveRoom(sendResponse) {
    try {
      if (this.roomId && this.isHost) {
        // El host elimina la sala (intentar, pero no bloquear si falla)
        try {
            await fetch(`http://127.0.0.1:8080/rooms/${this.roomId}`, {
              method: 'DELETE',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ senderId: this.senderId })
            });
        } catch (e) {
            console.warn('Could not delete room on server:', e);
        }
      }
      
      this.roomId = null;
      this.senderId = null;
      this.isHost = false;
      
      await this.clearSession();
      
      // Notificar a todas las pestañas
      this.broadcastToAllTabs({ type: 'leaveRoom' });
      
      sendResponse({ success: true });
      
    } catch (error) {
      console.error('Leave room error:', error);
      sendResponse({ success: false, error: error.message });
    }
  }

  async handleGetRoomState(request, sendResponse) {
    try {
      if (!this.roomId) {
        sendResponse({ success: false, error: 'Not in a room' });
        return;
      }
      
      const response = await fetch(`http://127.0.0.1:8080/rooms/${this.roomId}`);
      
      if (response.status === 404) {
        console.warn('Room not found on server (404), clearing background session');
        
        // Limpiar estado local
        this.roomId = null;
        this.senderId = null;
        this.isHost = false;
        await this.clearSession();
        
        // Avisar a todos que salimos
        this.broadcastToAllTabs({ type: 'leaveRoom' });
        
        sendResponse({ success: false, error: 'room_not_found' });
        return;
      }

      if (!response.ok) throw new Error('Failed to get room state');
      
      const roomData = await response.json();
      sendResponse({ success: true, room: roomData });
      
    } catch (error) {
      console.error('Get room state error:', error);
      sendResponse({ success: false, error: error.message });
    }
  }

  handleGetSessionState(sendResponse) {
    sendResponse({
      roomId: this.roomId,
      senderId: this.senderId,
      isHost: this.isHost
    });
  }

  async handleControlPlayback(request, sendResponse) {
    // Reenviar el comando a todas las pestañas de YouTube Music
    this.broadcastToAllTabs({
      type: 'controlPlayback',
      action: request.action,
      position: request.position
    });
    
    sendResponse({ success: true });
  }

  async saveSession() {
    await chrome.storage.local.set({
      roomId: this.roomId,
      senderId: this.senderId,
      isHost: this.isHost
    });
  }

  async restoreSession() {
    try {
      const data = await chrome.storage.local.get(['roomId', 'senderId', 'isHost']);
      if (data.roomId && data.senderId) {
        this.roomId = data.roomId;
        this.senderId = data.senderId;
        this.isHost = data.isHost;
        
        // Notificar a las pestañas existentes
        this.broadcastToAllTabs({
          type: 'sessionUpdate',
          roomId: this.roomId,
          senderId: this.senderId,
          isHost: this.isHost
        });
      }
    } catch (error) {
      console.error('Restore session error:', error);
    }
  }

  async clearSession() {
    await chrome.storage.local.remove(['roomId', 'senderId', 'isHost']);
  }

  broadcastToAllTabs(message) {
    chrome.tabs.query({ url: 'https://music.youtube.com/*' }, (tabs) => {
      tabs.forEach(tab => {
        this.sendToContentScript(tab.id, message);
      });
    });
  }

  sendToContentScript(tabId, message) {
    chrome.tabs.sendMessage(tabId, message).catch(error => {
      // Ignorar errores de pestañas sin content script
      if (!error.message.includes('Could not establish connection')) {
        console.warn('Send to content script error:', error);
      }
    });
  }

  // Notificaciones del sistema
  showNotification(title, message) {
    chrome.notifications.create({
      type: 'basic',
      iconUrl: 'assets/icons/icon48.png',
      title: title,
      message: message
    });
  }
}

// Inicializar el servicio
const backgroundService = new BackgroundService();