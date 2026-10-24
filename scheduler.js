/* Jaago — turns alarm settings into exact times and hands them to the native alarm plugin. */
(function () {
  const A = window.JaagoAstro;
  const HORIZON_DAYS = 7; // schedule one week ahead; refreshed every time the app opens or an alarm rings
  const MAX_ALARMS = 300;

  const SMART_LABELS = {
    brahma: 'Brahma Muhurat',
    suryoday: 'Suryoday',
    sandhya: 'Sandhya aarti',
    sehri: 'Sehri',
    iftar: 'Iftar',
    ekadashi: 'Ekadashi tomorrow',
  };

  function addDays(d, n) {
    const x = new Date(d.getFullYear(), d.getMonth(), d.getDate());
    x.setDate(x.getDate() + n);
    return x;
  }

  function atTime(day, h, m) {
    return new Date(day.getFullYear(), day.getMonth(), day.getDate(), h, m, 0, 0);
  }

  /** Every alarm moment in the next week, sorted by time. */
  function buildOccurrences(state, now) {
    now = now || new Date();
    const nowMs = now.getTime();
    const out = [];
    const today = addDays(now, 0);

    // Custom alarms
    for (const a of state.alarms) {
      if (!a.on) continue;
      const repeat = a.days && a.days.length > 0;
      for (let i = 0; i <= HORIZON_DAYS; i++) {
        const day = addDays(today, i);
        if (repeat && !a.days.includes(day.getDay())) continue;
        const t = atTime(day, a.hour, a.min);
        if (t.getTime() <= nowMs) continue;
        out.push({ at: t.getTime(), label: a.label || 'Alarm', mission: a.mission || 'none', ref: a.id, kind: 'custom' });
        if (!repeat) break;
      }
    }

    // Smart (panchang / sun) alarms need a location
    const loc = state.location;
    if (loc) {
      const s = state.smart;
      for (let i = 0; i <= HORIZON_DAYS; i++) {
        const day = addDays(today, i);
        const t = A.dayTimes(day, loc.lat, loc.lng);
        const push = (key, date) => {
          if (!date) return;
          const at = Math.round(date.getTime() / 60000) * 60000; // ring on the whole minute
          if (at <= nowMs) return;
          out.push({ at, label: SMART_LABELS[key], mission: s[key].mission || 'none', ref: 'smart:' + key, kind: 'smart' });
        };
        if (s.brahma.on) push('brahma', t.brahmaStart);
        if (s.suryoday.on) push('suryoday', t.sunrise);
        if (s.sandhya.on) push('sandhya', t.sunset);
        if (A.isRamadan(day)) {
          if (s.sehri.on && t.fajr) push('sehri', new Date(t.fajr.getTime() - (s.sehri.before || 45) * 60000));
          if (s.iftar.on) push('iftar', t.sunset);
        }
        if (s.ekadashi.on) {
          const next = addDays(day, 1);
          const nextTimes = A.dayTimes(next, loc.lat, loc.lng);
          if (nextTimes.sunrise) {
            const tithi = A.tithiAt(nextTimes.sunrise);
            if (tithi === 11 || tithi === 26) push('ekadashi', atTime(day, s.ekadashi.hour || 21, 0));
          }
        }
      }
    }

    // A pending snooze
    if (state.snooze && state.snooze.at > nowMs) {
      out.push({ at: state.snooze.at, label: state.snooze.label, mission: state.snooze.mission, ref: state.snooze.ref, kind: 'snooze', count: state.snooze.count });
    }

    out.sort((x, y) => x.at - y.at);
    const trimmed = out.slice(0, MAX_ALARMS);
    trimmed.forEach((o, i) => (o.id = 1000 + i)); // Android request codes
    return trimmed;
  }

  // ---- Native bridge (with a browser stand-in so the UI can be previewed on a computer) ----
  const isNative = !!(window.Capacitor && window.Capacitor.isNativePlatform && window.Capacitor.isNativePlatform());

  const webMock = (function () {
    const listeners = [];
    let ringing = null;
    return {
      setAlarms: async ({ alarms }) => {
        console.log('[mock] scheduled', alarms.length, 'alarms', alarms.slice(0, 5));
        return {};
      },
      getRinging: async () => (ringing ? { alarm: ringing } : {}),
      stopRinging: async () => { ringing = null; return {}; },
      pauseSound: async () => ({}),
      resumeSound: async () => ({}),
      checkSetup: async () => ({ exact: true, notifications: true, fullScreen: true, battery: true }),
      requestNotifications: async () => ({ granted: true }),
      openSettings: async () => ({}),
      addListener: async (name, cb) => { listeners.push(cb); return { remove() {} }; },
      _fakeRing: (alarm) => { ringing = alarm; listeners.forEach((cb) => cb({ alarm })); },
    };
  })();

  const Native = isNative ? window.Capacitor.Plugins.JaagoAlarm : webMock;

  async function sync(state) {
    const alarms = buildOccurrences(state).map((o) => ({
      id: o.id,
      at: o.at,
      label: o.label,
      mission: o.mission,
      ref: o.ref,
      kind: o.kind,
      count: o.count || 0,
    }));
    try {
      await Native.setAlarms({ alarms });
    } catch (e) {
      console.error('Scheduling failed', e);
    }
    return alarms;
  }

  window.JaagoScheduler = { buildOccurrences, sync, Native, isNative, SMART_LABELS };
})();
