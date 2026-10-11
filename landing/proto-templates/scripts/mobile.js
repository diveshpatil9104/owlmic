// Owlmic Mobile Portrait View Script

document.addEventListener('DOMContentLoaded', () => {
    const root = document.getElementById('view-mobile');
    if (!root) return;

    // Elements
    const cameraTile = root.querySelector('.js-mobile-camera');
    const cameraCanvas = root.querySelector('.js-mobile-camera-canvas');
    const cameraOffFace = root.querySelector('.js-mobile-camera-off');
    const cameraOverlay = root.querySelector('.js-mobile-camera-overlay');
    const flipBtn = root.querySelector('.js-mobile-flip');
    const cameraCaption = root.querySelector('.js-mobile-camera-caption');

    const micTile = root.querySelector('.js-mobile-mic');
    const micState = root.querySelector('.js-mobile-mic-state');
    const micMeter = root.querySelector('.js-mobile-mic-meter');

    const speakerTile = root.querySelector('.js-mobile-speaker');
    const speakerState = root.querySelector('.js-mobile-speaker-state');
    const speakerDetail = root.querySelector('.js-mobile-speaker-detail');

    const statusPc = root.querySelector('.js-mobile-status-pc');
    const statusText = root.querySelector('.js-mobile-status-text');
    const indicatorIcon = root.querySelector('.js-mobile-indicator-icon');

    const settingsBtn = root.querySelector('.js-mobile-settings-btn');
    const settingsSheet = root.querySelector('.js-mobile-settings-sheet');
    const settingsClose = root.querySelector('.js-mobile-settings-close');

    // Register canvas with central renderer
    if (cameraCanvas) {
        cameraCanvas.width = 375;
        cameraCanvas.height = 360;
        OwlmicStore.registerCameraCanvas(cameraCanvas);
    }

    // Event listeners
    if (cameraTile) {
        cameraTile.addEventListener('click', (e) => {
            if (e.target.closest('.camera-flip-btn')) return;
            OwlmicStore.toggleCamera();
        });
    }

    if (flipBtn) {
        flipBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            OwlmicStore.flipLens();
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

    if (settingsBtn) {
        settingsBtn.addEventListener('click', () => {
            OwlmicStore.openMobileSettings(true);
        });
    }

    if (settingsClose) {
        settingsClose.addEventListener('click', () => {
            OwlmicStore.openMobileSettings(false);
        });
    }

    // Segmented settings options click delegation
    if (settingsSheet) {
        settingsSheet.addEventListener('click', (e) => {
            const btn = e.target.closest('.segmented-opt');
            if (!btn) return;
            const action = btn.dataset.action;
            const val = btn.dataset.value;
            if (action === 'quality') OwlmicStore.cycleQuality();
            else if (action === 'fps') OwlmicStore.cycleFps();
            else if (action === 'noise') OwlmicStore.cycleNoiseReduction();
            else if (action === 'boost') OwlmicStore.cycleBoost();
            else if (action === 'lens') OwlmicStore.flipLens();
        });
    }

    // State subscriber
    OwlmicStore.subscribe((state) => {
        // Camera tile
        if (state.camera && (state.connection === 'connected' || state.connection === 'repair')) {
            if (cameraCanvas) cameraCanvas.style.display = 'block';
            if (cameraOverlay) cameraOverlay.style.display = 'flex';
            if (cameraOffFace) cameraOffFace.style.display = 'none';
            if (cameraCaption) cameraCaption.textContent = `${state.cameraQuality} · ${state.cameraFps} fps`;
        } else {
            if (cameraCanvas) cameraCanvas.style.display = 'none';
            if (cameraOverlay) cameraOverlay.style.display = 'none';
            if (cameraOffFace) cameraOffFace.style.display = 'flex';
        }

        // Mic tile
        if (state.mic && state.connection === 'connected') {
            micTile.classList.add('is-on');
            micState.textContent = 'On';
            if (micMeter) {
                micMeter.style.display = 'block';
                micMeter.style.width = Math.min(100, Math.max(0, state.audioLevel * 100)) + '%';
            }
        } else {
            micTile.classList.remove('is-on');
            micState.textContent = 'Off';
            if (micMeter) micMeter.style.display = 'none';
        }

        // Speaker tile
        if (state.speaker && state.connection === 'connected') {
            speakerTile.classList.add('is-on');
            speakerState.textContent = 'On';
            if (speakerDetail) {
                speakerDetail.style.display = 'block';
                speakerDetail.textContent = state.speakerOutput;
            }
        } else {
            speakerTile.classList.remove('is-on');
            speakerState.textContent = 'Off';
            if (speakerDetail) speakerDetail.style.display = 'none';
        }

        // Status row & Indicator
        if (state.connection === 'connected') {
            statusPc.textContent = state.pcName;
            statusText.textContent = 'Connected';
            indicatorIcon.style.color = 'var(--color-green)';
        } else if (state.connection === 'approving') {
            statusPc.textContent = state.pcName;
            statusText.innerHTML = `Approve on your PC · <span class="phone-status-code">${state.code}</span>`;
            indicatorIcon.style.color = 'var(--color-yellow)';
        } else if (state.connection === 'reconnecting') {
            statusPc.textContent = state.pcName;
            statusText.textContent = 'Reconnecting';
            indicatorIcon.style.color = 'var(--color-yellow)';
        } else if (state.connection === 'repair') {
            statusPc.textContent = state.pcName;
            statusText.textContent = 'Owlmic Cam needs a repair';
            indicatorIcon.style.color = 'var(--color-yellow)';
        } else {
            statusPc.textContent = 'Looking for your PC';
            statusText.textContent = 'Open Owlmic on your PC';
            indicatorIcon.style.color = 'var(--color-red)';
        }

        // Settings sheet
        if (state.mobileSettingsOpen) {
            settingsSheet.classList.add('open');
        } else {
            settingsSheet.classList.remove('open');
        }
    });
});
