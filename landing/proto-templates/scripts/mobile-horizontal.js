// Owlmic Mobile Horizontal / Landscape View Script

document.addEventListener('DOMContentLoaded', () => {
    const root = document.getElementById('view-mobile-horizontal');
    if (!root) return;

    // Elements
    const cameraTile = root.querySelector('.js-horiz-camera');
    const cameraCanvas = root.querySelector('.js-horiz-camera-canvas');
    const cameraOffFace = root.querySelector('.js-horiz-camera-off');
    const cameraOverlay = root.querySelector('.js-horiz-camera-overlay');
    const flipBtn = root.querySelector('.js-horiz-flip');
    const cameraCaption = root.querySelector('.js-horiz-camera-caption');

    const micTile = root.querySelector('.js-horiz-mic');
    const micState = root.querySelector('.js-horiz-mic-state');
    const micMeter = root.querySelector('.js-horiz-mic-meter');

    const speakerTile = root.querySelector('.js-horiz-speaker');
    const speakerState = root.querySelector('.js-horiz-speaker-state');
    const speakerDetail = root.querySelector('.js-horiz-speaker-detail');

    const statusPc = root.querySelector('.js-horiz-status-pc');
    const statusText = root.querySelector('.js-horiz-status-text');
    const indicatorIcon = root.querySelector('.js-horiz-indicator-icon');

    const settingsBtn = root.querySelector('.js-horiz-settings-btn');
    const settingsSheet = root.querySelector('.js-horiz-settings-sheet');
    const settingsClose = root.querySelector('.js-horiz-settings-close');

    // Register canvas with central renderer
    if (cameraCanvas) {
        cameraCanvas.width = 505;
        cameraCanvas.height = 375;
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
            OwlmicStore.openMobileHorizontalSettings(true);
        });
    }

    if (settingsClose) {
        settingsClose.addEventListener('click', () => {
            OwlmicStore.openMobileHorizontalSettings(false);
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
        } else {
            statusPc.textContent = 'Looking for your PC';
            statusText.textContent = 'Open Owlmic on your PC';
            indicatorIcon.style.color = 'var(--color-red)';
        }

        // Settings sheet
        if (state.mobileHorizontalSettingsOpen) {
            settingsSheet.classList.add('open');
        } else {
            settingsSheet.classList.remove('open');
        }
    });
});
