// Owlmic PC Win32 Flyout Panel Script

document.addEventListener('DOMContentLoaded', () => {
    const root = document.getElementById('view-pc');
    if (!root) return;

    // Screens
    const screenMain = root.querySelector('.js-pc-screen-main');
    const screenGate = root.querySelector('.js-pc-screen-gate');
    const screenSettings = root.querySelector('.js-pc-screen-settings');

    // Main elements
    const cameraTile = root.querySelector('.js-pc-camera-tile');
    const cameraCanvas = root.querySelector('.js-pc-camera-canvas');
    const cameraWatermark = root.querySelector('.js-pc-camera-watermark');
    const cameraOffFace = root.querySelector('.js-pc-camera-off');
    const cameraOffText = root.querySelector('.js-pc-camera-off-text');
    const repairBar = root.querySelector('.js-pc-repair-bar');
    const repairBtn = root.querySelector('.js-pc-repair-btn');

    const micTile = root.querySelector('.js-pc-mic-tile');
    const micState = root.querySelector('.js-pc-mic-state');

    const speakerTile = root.querySelector('.js-pc-speaker-tile');
    const speakerState = root.querySelector('.js-pc-speaker-state');

    const phoneName = root.querySelector('.js-pc-phone-name');
    const phoneState = root.querySelector('.js-pc-phone-state');

    const btnFraming = root.querySelector('.js-pc-btn-framing');
    const btnMirror = root.querySelector('.js-pc-btn-mirror');
    const btnLens = root.querySelector('.js-pc-btn-lens');
    const btnSettings = root.querySelector('.js-pc-btn-settings');
    const indicatorIcon = root.querySelector('.js-pc-indicator-icon');

    // Gate elements
    const gateTitle = root.querySelector('.js-pc-gate-title');
    const gateCode = root.querySelector('.js-pc-gate-code');
    const gateAllow = root.querySelector('.js-pc-gate-allow');
    const gateDeny = root.querySelector('.js-pc-gate-deny');

    // Settings elements
    const settingsBack = root.querySelector('.js-pc-settings-back');
    const settingsScroll = root.querySelector('.js-pc-settings-scroll');

    // Register canvas
    if (cameraCanvas) {
        cameraCanvas.width = 352;
        cameraCanvas.height = 198;
        OwlmicStore.registerCameraCanvas(cameraCanvas);
    }

    // Event listeners - Main Screen
    if (cameraTile) {
        cameraTile.addEventListener('click', (e) => {
            if (e.target.closest('.js-pc-repair-btn')) return;
            OwlmicStore.toggleCamera();
        });
    }

    if (repairBtn) {
        repairBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            OwlmicStore.repairNow();
        });
    }

    if (micTile) {
        micTile.addEventListener('click', () => {
            OwlmicStore.toggleMic();
        });
    }

    if (speakerTile) {
        speakerTile.addEventListener('click', () => {
            OwlmicStore.toggleSpeaker();
        });
    }

    if (btnFraming) {
        btnFraming.addEventListener('click', () => {
            OwlmicStore.cycleFraming();
        });
    }

    if (btnMirror) {
        btnMirror.addEventListener('click', () => {
            OwlmicStore.toggleMirror();
        });
    }

    if (btnLens) {
        btnLens.addEventListener('click', () => {
            OwlmicStore.flipLens();
        });
    }

    if (btnSettings) {
        btnSettings.addEventListener('click', () => {
            OwlmicStore.setPcScreen('settings');
        });
    }

    const btnIndicator = root.querySelector('.pc-btn-indicator');
    if (btnIndicator) {
        btnIndicator.addEventListener('click', () => {
            const current = OwlmicStore.getState().connection;
            if (current === 'connected') OwlmicStore.setConnectionState('approving');
            else if (current === 'approving') OwlmicStore.setConnectionState('reconnecting');
            else if (current === 'reconnecting') OwlmicStore.setConnectionState('not_connected');
            else OwlmicStore.setConnectionState('connected');
        });
    }

    // Event listeners - Gate Screen
    if (gateAllow) {
        gateAllow.addEventListener('click', () => {
            OwlmicStore.allowApproval();
        });
    }

    if (gateDeny) {
        gateDeny.addEventListener('click', () => {
            OwlmicStore.denyApproval();
        });
    }

    // Event listeners - Settings Screen
    if (settingsBack) {
        settingsBack.addEventListener('click', () => {
            OwlmicStore.setPcScreen('main');
        });
    }

    if (settingsScroll) {
        settingsScroll.addEventListener('click', (e) => {
            const row = e.target.closest('.pc-set-row');
            if (!row || row.classList.contains('pc-set-header')) return;
            const settingId = row.dataset.setting;
            if (settingId === 'quality') OwlmicStore.cycleQuality();
            else if (settingId === 'fps') OwlmicStore.cycleFps();
            else if (settingId === 'noise') OwlmicStore.cycleNoiseReduction();
            else if (settingId === 'boost') OwlmicStore.cycleBoost();
            else if (settingId === 'lens') OwlmicStore.flipLens();
            else if (settingId === 'framing') OwlmicStore.cycleFraming();
            else if (settingId === 'mirror') OwlmicStore.toggleMirror();
        });
    }

    // State subscriber
    OwlmicStore.subscribe((state) => {
        // Screen visibility
        if (state.pcScreen === 'gate' || state.connection === 'approving') {
            screenMain.classList.remove('active');
            screenGate.classList.add('active');
            screenSettings.classList.remove('active');
        } else if (state.pcScreen === 'settings') {
            screenMain.classList.remove('active');
            screenGate.classList.remove('active');
            screenSettings.classList.add('active');
        } else {
            screenMain.classList.add('active');
            screenGate.classList.remove('active');
            screenSettings.classList.remove('active');
        }

        // Camera Preview
        if (state.camera && (state.connection === 'connected' || state.connection === 'repair')) {
            if (cameraCanvas) cameraCanvas.style.display = 'block';
            if (cameraOffFace) cameraOffFace.style.display = 'none';
            if (cameraWatermark) cameraWatermark.style.display = 'none';
        } else {
            if (cameraCanvas) cameraCanvas.style.display = 'none';
            if (cameraOffFace) cameraOffFace.style.display = 'flex';
            if (cameraWatermark) cameraWatermark.style.display = 'flex';
            if (cameraOffText) {
                if (state.connection !== 'connected' && state.connection !== 'repair') {
                    cameraOffText.textContent = 'No phone connected';
                } else {
                    cameraOffText.textContent = 'Camera off';
                }
            }
        }

        // Repair Banner
        if (repairBar) {
            if (state.connection === 'repair') {
                repairBar.style.display = 'flex';
                if (repairBtn) repairBtn.textContent = state.repairing ? 'Repairing…' : 'Repair';
            } else {
                repairBar.style.display = 'none';
            }
        }

        // Mic Tile
        if (state.connection !== 'connected' && state.connection !== 'repair') {
            micTile.className = 'pc-mic-tile pc-feature-dimmed js-pc-mic-tile';
            if (micState) micState.textContent = 'Off';
        } else if (state.mic) {
            micTile.className = 'pc-mic-tile is-on pc-feature-active js-pc-mic-tile';
            if (micState) micState.textContent = 'On';
        } else {
            micTile.className = 'pc-mic-tile js-pc-mic-tile';
            if (micState) micState.textContent = 'Off';
        }

        const micMeter = root.querySelector('.js-pc-mic-meter');
        if (micMeter) {
            micMeter.style.width = state.mic ? `${Math.min(100, Math.max(0, state.audioLevel * 100))}%` : '0%';
        }

        // Speaker Tile
        if (state.connection !== 'connected' && state.connection !== 'repair') {
            speakerTile.className = 'pc-speaker-tile pc-feature-dimmed js-pc-speaker-tile';
            if (speakerState) speakerState.textContent = 'Off';
        } else if (state.speaker) {
            speakerTile.className = 'pc-speaker-tile is-on pc-feature-active js-pc-speaker-tile';
            if (speakerState) speakerState.textContent = 'On';
        } else {
            speakerTile.className = 'pc-speaker-tile js-pc-speaker-tile';
            if (speakerState) speakerState.textContent = 'Off';
        }

        // Phone Tile
        if (state.connection === 'connected' || state.connection === 'repair') {
            phoneName.textContent = state.phoneName;
            phoneState.textContent = 'Connected';
        } else if (state.connection === 'reconnecting') {
            phoneName.textContent = state.phoneName;
            phoneState.textContent = 'Reconnecting';
        } else {
            phoneName.textContent = 'No phone connected';
            phoneState.textContent = 'Disconnected';
        }

        const statusDot = root.querySelector('.js-pc-status-dot');
        if (statusDot) {
            statusDot.style.backgroundColor = state.connection === 'connected'
                ? 'var(--color-green)'
                : (state.connection === 'approving' || state.connection === 'reconnecting' || state.connection === 'repair')
                ? 'var(--color-yellow)'
                : 'var(--color-red)';
        }

        // Bottom row
        const framingText = root.querySelector('.js-pc-btn-framing-text');
        if (framingText) framingText.textContent = state.cameraFraming;

        if (btnMirror) {
            if (state.cameraMirror) {
                btnMirror.classList.add('active');
            } else {
                btnMirror.classList.remove('active');
            }
        }

        const lensText = root.querySelector('.js-pc-btn-lens-text');
        if (lensText) lensText.textContent = state.cameraLens;

        // Indicator
        if (indicatorIcon) {
            if (state.connection === 'connected') {
                indicatorIcon.style.color = 'var(--color-green)';
            } else if (state.connection === 'approving' || state.connection === 'reconnecting' || state.connection === 'repair') {
                indicatorIcon.style.color = 'var(--color-yellow)';
            } else {
                indicatorIcon.style.color = 'var(--color-red)';
            }
        }

        const indicatorText = root.querySelector('.js-pc-indicator-text');
        if (indicatorText) {
            indicatorText.textContent = state.connection === 'connected' ? 'Linked' : 'Offline';
        }

        // Gate Screen
        if (gateTitle) gateTitle.textContent = `${state.phoneName} wants to connect`;
        if (gateCode) gateCode.textContent = state.code;

        // Settings Screen values update
        const valQuality = root.querySelector('.js-pc-val-quality');
        if (valQuality) valQuality.textContent = state.cameraQuality;

        const valFps = root.querySelector('.js-pc-val-fps');
        if (valFps) valFps.textContent = state.cameraFps;

        const valNoise = root.querySelector('.js-pc-val-noise');
        if (valNoise) valNoise.textContent = state.micNoise;

        const valBoost = root.querySelector('.js-pc-val-boost');
        if (valBoost) valBoost.textContent = state.micBoost;
    });
});
