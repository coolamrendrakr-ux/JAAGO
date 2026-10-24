/* Jaago — app state (localStorage) and voice notes (IndexedDB). */
(function () {
  const KEY = 'jaago.state.v1';

  const DEFAULT_STATE = {
    alarms: [], // custom alarms
    smart: {
      brahma: { on: false, mission: 'none' },
      suryoday: { on: false, mission: 'none' },
      sandhya: { on: false, mission: 'none' },
      sehri: { on: false, mission: 'none', before: 45 },
      iftar: { on: false, mission: 'none' },
      ekadashi: { on: false, mission: 'none', hour: 21 },
    },
    location: null, // { lat, lng, name }
    snoozeMinutes: 5,
    snoozeMax: 2,
    snooze: null, // { at, label, mission, ref, count }
    setupDone: false,
  };

  function load() {
    try {
      const raw = localStorage.getItem(KEY);
      if (!raw) return JSON.parse(JSON.stringify(DEFAULT_STATE));
      const s = JSON.parse(raw);
      const base = JSON.parse(JSON.stringify(DEFAULT_STATE));
      return Object.assign(base, s, { smart: Object.assign(base.smart, s.smart || {}) });
    } catch (e) {
      return JSON.parse(JSON.stringify(DEFAULT_STATE));
    }
  }

  function save(state) {
    try {
      localStorage.setItem(KEY, JSON.stringify(state));
    } catch (e) {
      console.warn('Could not save state', e);
    }
  }

  function newId() {
    return 'a' + Date.now().toString(36) + Math.random().toString(36).slice(2, 6);
  }

  // ---- Voice notes in IndexedDB ----
  let dbPromise = null;
  function db() {
    if (dbPromise) return dbPromise;
    dbPromise = new Promise((resolve, reject) => {
      const req = indexedDB.open('jaago', 1);
      req.onupgradeneeded = () => req.result.createObjectStore('voice');
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
    return dbPromise;
  }

  async function putVoice(id, blob) {
    const d = await db();
    return new Promise((resolve, reject) => {
      const tx = d.transaction('voice', 'readwrite');
      tx.objectStore('voice').put(blob, id);
      tx.oncomplete = () => resolve();
      tx.onerror = () => reject(tx.error);
    });
  }

  async function getVoice(id) {
    const d = await db();
    return new Promise((resolve, reject) => {
      const req = d.transaction('voice').objectStore('voice').get(id);
      req.onsuccess = () => resolve(req.result || null);
      req.onerror = () => reject(req.error);
    });
  }

  async function deleteVoice(id) {
    const d = await db();
    return new Promise((resolve) => {
      const tx = d.transaction('voice', 'readwrite');
      tx.objectStore('voice').delete(id);
      tx.oncomplete = () => resolve();
      tx.onerror = () => resolve();
    });
  }

  window.JaagoStore = { load, save, newId, putVoice, getVoice, deleteVoice };
})();
