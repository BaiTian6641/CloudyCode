/* Local design interactions only. CSP blocks all network connections. */
(() => {
  'use strict';
  const byId = id => document.getElementById(id);
  const appWindow = byId('app-window');
  const workspace = byId('workspace');
  const palette = byId('palette-dialog');
  let app = 'console';
  let currentWorkspace = 'fpga';
  let desktopView = 'overview';
  let sessionCount = 0;
  let paletteIndex = 0;
  let matchingActions = [];
  let returnFocus = null;
  let announcementTimer;
  let deviceReleased = false;
  const profiles = {
    fpga: {title: 'FPGA controller', host: 'MS-02Ultra', environment: 'Linux', description: 'Build, inspect, and stay in flow.', root: '~/projects/controller', persistence: 'Durable · tmux'},
    studio: {title: 'Studio tools', host: 'Mac Studio', environment: 'macOS', description: 'A focused place for your studio workflows.', root: '~/projects/studio', persistence: 'Durable · tmux'},
    lab: {title: 'Lab notebooks', host: 'ZBook', environment: 'Demo environment', description: 'Keep experiments and artifacts close at hand.', root: '~/projects/lab', persistence: 'Unknown · not qualified'}
  };

  function announce(message) {
    clearTimeout(announcementTimer);
    byId('announcement').textContent = message;
    byId('announcement').classList.add('visible');
    announcementTimer = setTimeout(() => byId('announcement').classList.remove('visible'), 4200);
  }

  function setInspector(visible) {
    workspace.dataset.inspector = String(visible);
    byId('inspector').hidden = !visible;
    byId('inspector-toggle').setAttribute('aria-expanded', String(visible));
    byId('inspector-toggle').setAttribute('aria-label', visible ? 'Hide details' : 'Show details');
    if (!visible && byId('inspector').contains(document.activeElement)) byId('inspector-toggle').focus();
  }

  function setTheme(theme) {
    document.documentElement.dataset.theme = theme;
    byId('theme-label').textContent = theme === 'light' ? 'Dark appearance' : 'Light appearance';
    byId('theme-toggle').setAttribute('aria-label', 'Switch to ' + (theme === 'light' ? 'dark' : 'light') + ' appearance');
  }

  function setConnection(value) {
    byId('connection').value = value;
    const host = profiles[currentWorkspace].host;
    const badge = byId('connection-badge');
    const banner = byId('connection-banner');
    badge.className = 'badge ' + (value === 'connected' ? 'success-badge' : value === 'reconnecting' ? 'warning-badge' : 'neutral-badge');
    badge.textContent = (value === 'connected' ? 'Connected' : value === 'reconnecting' ? 'Reconnecting' : 'Offline') + ' · demo';
    banner.hidden = value === 'connected';
    banner.textContent = value === 'reconnecting' ? 'Reconnecting to ' + host + '. Your layout is preserved. No input or command will be replayed.' : 'Offline. Previous output remains readable; device ownership must be reconciled before reassignment.';
    byId('status-connection').textContent = (value === 'connected' ? 'Connected to ' : value === 'reconnecting' ? 'Reconnecting to ' : 'Disconnected from ') + host + ' · demo';
    byId('status-measurement').textContent = value === 'connected' ? 'Illustrative: 14 ms RTT · 4.2 MB payload' : 'Path unknown · last measurements are stale';
    byId('status-dot').dataset.state = value;
    byId('status-dot').style.background = value === 'connected' ? 'var(--cw-primary)' : value === 'reconnecting' ? 'var(--cw-warning)' : 'var(--cw-outline)';
    renderDevice();
  }

  function renderDevice() {
    const connected = byId('connection').value === 'connected';
    const protectedOperation = byId('protected-operation').checked;
    byId('device-owner').textContent = deviceReleased ? 'No consumer' : 'MS-02Ultra';
    byId('device-badge').textContent = !connected ? 'Owner unknown · demo' : deviceReleased ? 'Available · demo' : 'In use · demo';
    byId('device-badge').className = 'badge ' + (!connected ? 'warning-badge' : deviceReleased ? 'neutral-badge' : 'success-badge');
    byId('owner-state').textContent = !connected ? 'Unknown; last confirmed state is stale' : deviceReleased ? 'Released in this design scenario' : 'Confirmed in this design scenario';
    byId('release-device').disabled = !connected || protectedOperation;
    byId('release-device').textContent = deviceReleased ? 'Assign demo device' : 'Release demo device';
    byId('protected-operation').disabled = deviceReleased;
    byId('device-help').textContent = !connected ? 'Reconnect and reconcile ownership before changing the consumer.' : protectedOperation ? 'Programming is protected in this scenario. Finish the operation before release.' : 'This changes the illustration only. No device is connected.';
  }

  function selectDestination(destination) {
    const hosts = destination === 'hosts';
    byId('hosts-view').hidden = !hosts;
    byId('console-view').hidden = hosts || app !== 'console';
    byId('desktop-view').hidden = hosts || app !== 'desktop';
    document.querySelectorAll('[data-destination]').forEach(button => {
      const selected = button.dataset.destination === destination;
      button.classList.toggle('current', selected);
      if (selected) button.setAttribute('aria-current', 'page'); else button.removeAttribute('aria-current');
    });
    workspace.classList.remove('navigation-open');
    byId('navigation-toggle').setAttribute('aria-expanded', 'false');
  }

  function setDesktopView(value) {
    desktopView = value;
    ['overview', 'files', 'devices', 'activity'].forEach(name => { byId('desktop-' + name).hidden = name !== value; });
    document.querySelectorAll('[data-desktop-view]').forEach(button => {
      const selected = button.dataset.desktopView === value;
      button.classList.toggle('selected', selected);
      button.setAttribute('aria-pressed', String(selected));
    });
    selectDestination(value);
  }

  function setApp(value) {
    app = value;
    appWindow.dataset.app = value;
    document.querySelectorAll('[data-app-choice]').forEach(button => {
      const selected = button.dataset.appChoice === value;
      button.classList.toggle('selected', selected);
      button.setAttribute('aria-pressed', String(selected));
    });
    byId('desktop-navigation').hidden = value !== 'desktop';
    byId('main-action').querySelector('span').textContent = value === 'console' ? 'New session' : 'Open desktop';
    if (value === 'desktop') setDesktopView(desktopView); else selectDestination('overview');
  }

  function setWorkspace(value) {
    currentWorkspace = value;
    const profile = profiles[value];
    byId('workspace-title').textContent = profile.title;
    byId('workspace-description').textContent = profile.description;
    byId('context-breadcrumb').textContent = 'Workspaces / ' + profile.host + ' / ' + profile.environment;
    byId('inspector-host').textContent = profile.host;
    byId('inspector-environment').textContent = profile.environment + ' · synthetic environment';
    byId('project-root').textContent = profile.root;
    byId('persistence-label').textContent = profile.persistence;
    document.querySelectorAll('[data-workspace]').forEach(button => {
      const selected = button.dataset.workspace === value;
      button.classList.toggle('active', selected);
      button.setAttribute('aria-pressed', String(selected));
    });
    setConnection(byId('connection').value);
    selectDestination(app === 'console' ? 'overview' : desktopView);
    announce('Selected ' + profile.title + '. Terminal and scene content remain illustrative.');
  }

  function selectSession(button) {
    document.querySelectorAll('.session-tab').forEach(tab => {
      const selected = tab === button;
      tab.classList.toggle('active', selected);
      tab.setAttribute('aria-selected', String(selected));
      tab.tabIndex = selected ? 0 : -1;
    });
    byId('terminal-workspace').setAttribute('aria-labelledby', button.id);
    const isBuild = button.dataset.session === 'build';
    byId('narrow-pane-switch').hidden = !isBuild;
    if (!isBuild) setNarrowPane('build');
    byId('terminal-workspace').classList.toggle('single-session', !isBuild);
    byId('terminal-workspace').style.gridTemplateColumns = isBuild ? '' : 'minmax(0, 1fr)';
    document.querySelector('.review-pane').hidden = !isBuild;
    document.querySelector('.primary-pane .pane-label > span:last-child').textContent = isBuild ? 'tmux · build' : 'tmux · ' + button.dataset.session;
  }

  function setNarrowPane(value) {
    byId('terminal-workspace').dataset.focusedPane = value;
    document.querySelectorAll('[data-narrow-pane]').forEach(button => {
      const selected = button.dataset.narrowPane === value;
      button.classList.toggle('selected', selected);
      button.setAttribute('aria-pressed', String(selected));
    });
  }

  function newSession() {
    setApp('console');
    sessionCount += 1;
    const button = document.createElement('button');
    button.className = 'session-tab';
    button.type = 'button';
    button.id = 'tab-demo-' + sessionCount;
    button.dataset.session = 'demo-' + sessionCount;
    button.setAttribute('role', 'tab');
    button.setAttribute('aria-controls', 'terminal-workspace');
    button.textContent = 'Demo session ' + sessionCount;
    byId('session-tabs').append(button);
    selectSession(button);
    button.focus();
    announce('Created a local design tab. No SSH connection was started.');
  }

  const actions = [
    {label: 'Open Cloudy Console', group: 'Application', run: () => { setApp('console'); byId('console-choice').focus(); }},
    {label: 'Open Cloudy Desktop', group: 'Application', run: () => { setApp('desktop'); byId('desktop-choice').focus(); }},
    {label: 'New demo terminal session', group: 'Console', run: newSession},
    {label: 'Toggle workspace details', group: 'View', run: () => setInspector(byId('inspector').hidden)},
    {label: 'Toggle light or dark appearance', group: 'Appearance', run: () => setTheme(document.documentElement.dataset.theme === 'light' ? 'dark' : 'light')},
    {label: 'Review project files', group: 'Desktop', run: () => { setApp('desktop'); setDesktopView('files'); }}
  ];

  function renderActions() {
    const query = byId('palette-input').value.toLowerCase();
    matchingActions = actions.filter(action => action.label.toLowerCase().includes(query));
    paletteIndex = Math.min(paletteIndex, Math.max(0, matchingActions.length - 1));
    const results = byId('palette-results');
    results.replaceChildren();
    matchingActions.forEach((action, index) => {
      const button = document.createElement('button');
      button.type = 'button'; button.className = 'palette-result'; button.id = 'palette-action-' + index;
      button.setAttribute('role', 'option'); button.setAttribute('aria-selected', String(index === paletteIndex)); button.tabIndex = -1;
      const label = document.createElement('span'); label.textContent = action.label;
      const group = document.createElement('small'); group.textContent = action.group;
      button.append(label, group);
      button.addEventListener('click', () => runAction(index)); results.append(button);
    });
    if (matchingActions.length) byId('palette-input').setAttribute('aria-activedescendant', 'palette-action-' + paletteIndex);
    else { byId('palette-input').removeAttribute('aria-activedescendant'); const empty = document.createElement('p'); empty.textContent = 'No matching action'; results.append(empty); }
  }

  function runAction(index) {
    const action = matchingActions[index];
    if (!action) return;
    returnFocus = null;
    palette.close();
    action.run();
  }

  function openPalette() {
    if (palette.open) return;
    returnFocus = document.activeElement;
    byId('palette-input').value = ''; paletteIndex = 0; renderActions(); palette.showModal(); byId('palette-input').focus();
  }

  document.querySelectorAll('[data-app-choice]').forEach(button => button.addEventListener('click', () => setApp(button.dataset.appChoice)));
  document.querySelectorAll('[data-narrow-pane]').forEach(button => button.addEventListener('click', () => setNarrowPane(button.dataset.narrowPane)));
  document.querySelectorAll('[data-workspace]').forEach(button => button.addEventListener('click', () => setWorkspace(button.dataset.workspace)));
  document.querySelectorAll('[data-destination]').forEach(button => button.addEventListener('click', () => {
    const destination = button.dataset.destination;
    if (destination === 'hosts') selectDestination(destination);
    else if (app === 'desktop') setDesktopView(destination);
    else selectDestination('overview');
  }));
  document.querySelectorAll('[data-desktop-view]').forEach(button => button.addEventListener('click', () => setDesktopView(button.dataset.desktopView)));
  document.querySelectorAll('[data-show-files]').forEach(button => button.addEventListener('click', () => setDesktopView('files')));
  document.querySelectorAll('[data-file]').forEach(button => button.addEventListener('click', () => {
    byId('selected-file-section').hidden = false; byId('selected-file-name').textContent = button.dataset.file; setInspector(true); announce('Showing details for ' + button.dataset.file);
  }));
  byId('theme-toggle').addEventListener('click', () => setTheme(document.documentElement.dataset.theme === 'light' ? 'dark' : 'light'));
  byId('density').addEventListener('change', event => {
    const descriptions = {
      comfortable: 'Comfortable spacing. Nominal 48 pixel rows and controls.',
      standard: 'Standard pointer spacing. Nominal 40 pixel rows and controls.',
      compact: 'Compact pointer spacing. Nominal 32 pixel rows and controls.'
    };
    const density = event.target.value;
    if (!Object.hasOwn(descriptions, density)) return;
    // Include the separate command dialog in the same local specimen setting.
    document.documentElement.dataset.density = density;
    appWindow.dataset.density = density;
    announce(descriptions[density] + ' Text size is unchanged; content may grow. Illustrative layout only.');
  });
  byId('connection').addEventListener('change', event => setConnection(event.target.value));
  byId('inspector-toggle').addEventListener('click', () => setInspector(byId('inspector').hidden));
  byId('inspector-close').addEventListener('click', () => setInspector(false));
  byId('open-desktop').addEventListener('click', () => { setApp('desktop'); byId('desktop-choice').focus(); });
  byId('main-action').addEventListener('click', () => {
    if (app === 'console') newSession();
    else { setConnection('connected'); announce('Desktop connection illustrated. The specimen opens no external client or stream.'); }
  });
  byId('desktop-demo').addEventListener('click', () => { setConnection('reconnecting'); announce('Showing the reconnect state. Choose Connected above to restore it.'); });
  byId('navigation-toggle').addEventListener('click', () => { const visible = workspace.classList.toggle('navigation-open'); byId('navigation-toggle').setAttribute('aria-expanded', String(visible)); });
  byId('session-tabs').addEventListener('click', event => { const tab = event.target.closest('.session-tab'); if (tab) selectSession(tab); });
  byId('session-tabs').addEventListener('keydown', event => {
    if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return;
    const tabs = [...document.querySelectorAll('.session-tab')]; const current = tabs.indexOf(document.activeElement); if (current < 0) return;
    event.preventDefault();
    const index = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (current + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
    selectSession(tabs[index]); tabs[index].focus();
  });
  byId('protected-operation').addEventListener('change', renderDevice);
  byId('release-device').addEventListener('click', () => {
    deviceReleased = !deviceReleased;
    renderDevice();
    announce(deviceReleased ? 'Demo device released locally.' : 'Demo device assigned locally.');
  });
  byId('command-trigger').addEventListener('click', openPalette);
  byId('palette-close').addEventListener('click', () => palette.close());
  palette.addEventListener('close', () => { if (returnFocus && returnFocus.isConnected) returnFocus.focus(); });
  byId('palette-input').addEventListener('input', () => { paletteIndex = 0; renderActions(); });
  byId('palette-input').addEventListener('keydown', event => {
    if (event.isComposing) return;
    if (['ArrowDown', 'ArrowUp'].includes(event.key) && matchingActions.length) { event.preventDefault(); paletteIndex = (paletteIndex + (event.key === 'ArrowDown' ? 1 : -1) + matchingActions.length) % matchingActions.length; renderActions(); }
    if (event.key === 'Enter') { event.preventDefault(); runAction(paletteIndex); }
  });
  document.addEventListener('keydown', event => {
    if (!event.isComposing && (event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 'p') { event.preventDefault(); openPalette(); }
    if (event.key === 'Escape' && !palette.open && workspace.classList.contains('navigation-open')) { workspace.classList.remove('navigation-open'); byId('navigation-toggle').setAttribute('aria-expanded', 'false'); byId('navigation-toggle').focus(); }
  });
  setTheme(window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light');
  setInspector(window.innerWidth >= 1280);
  window.matchMedia('(min-width: 1280px)').addEventListener('change', event => { if (!event.matches) setInspector(false); });
  setConnection('connected');
  selectDestination('overview');
})();
