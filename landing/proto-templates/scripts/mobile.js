// Owlmic Mobile Portrait View Script

document.addEventListener('DOMContentLoaded', () => {
    const root = document.querySelector('.phone-portrait-layout');
    if (!root) return;

    // Status Bar & Notification Shade Elements
    const clockEl = document.querySelector('.js-status-clock');
    const statusBar = document.querySelector('.js-phone-status-bar');
    const notifShade = document.querySelector('.js-phone-notif-shade');
    const notifDismiss = document.querySelector('.js-notif-dismiss');
    const notifTitle = document.querySelector('.js-notif-title');
    const notifDesc = document.querySelector('.js-notif-desc');
    const notifMuteBtn = document.querySelector('.js-notif-action-mute');
    const notifStatusBtn = document.querySelector('.js-notif-action-status');

    // Live clock update
    function updateClock() {
        if (!clockEl) return;
        const now = new Date();
        const hours = now.getHours();
        const minutes = String(now.getMinutes()).padStart(2, '0');
        clockEl.textContent = `${hours}:${minutes}`;
    }
    updateClock();
    setInterval(updateClock, 10000);

    // Notification shade interactive drag-down and toggle feature
    if (statusBar && notifShade) {
        let isDragging = false;
        let startY = 0;
        let currentDrag = 0;
        let hasMoved = false;

        function getIsLandscape() {
            const deviceMobile = document.querySelector('.js-device-mobile');
            return deviceMobile && deviceMobile.classList.contains('is-landscape');
        }

        function onPointerDown(e) {
            if (e.target.closest('.notif-card') && !e.target.closest('.notif-card-header')) return;
            isDragging = true;
            hasMoved = false;
            startY = e.clientY;
            currentDrag = 0;
            statusBar.style.cursor = 'grabbing';
            document.addEventListener('pointermove', onPointerMove);
            document.addEventListener('pointerup', onPointerUp);
        }

        function onPointerMove(e) {
            if (!isDragging) return;
            const dy = e.clientY - startY;
            if (Math.abs(dy) > 4) {
                hasMoved = true;
            }

            const isLandscape = getIsLandscape();
            const isOpen = notifShade.classList.contains('is-open');

            if (!isOpen) {
                currentDrag = Math.max(0, Math.min(dy, 120));
                if (!isLandscape) {
                    notifShade.style.transition = 'none';
                    notifShade.style.transform = `translateY(${Math.min(currentDrag, 100)}px)`;
                    notifShade.style.opacity = String(Math.min(1, currentDrag / 30));
                    notifShade.style.pointerEvents = 'none';
                } else {
                    notifShade.style.transition = 'none';
                    const pct = Math.max(0, Math.min(100, (currentDrag / 70) * 100));
                    notifShade.style.transform = `translate(${140 - (pct * 1.4)}%, -50%) rotate(90deg)`;
                    notifShade.style.opacity = String(Math.min(1, currentDrag / 30));
                    notifShade.style.pointerEvents = 'none';
                }
            } else {
                if (dy < 0) {
                    currentDrag = dy;
                    if (!isLandscape) {
                        notifShade.style.transition = 'none';
                        notifShade.style.transform = `translateY(${Math.max(-100, 38 + dy)}px)`;
                        notifShade.style.opacity = String(Math.max(0, 1 + (dy / 80)));
                    } else {
                        notifShade.style.transition = 'none';
                        const offsetPct = Math.min(140, Math.abs(dy) * 1.5);
                        notifShade.style.transform = `translate(${offsetPct}%, -50%) rotate(90deg)`;
                        notifShade.style.opacity = String(Math.max(0, 1 + (dy / 80)));
                    }
                }
            }
        }

        function onPointerUp(e) {
            if (!isDragging) return;
            isDragging = false;
            statusBar.style.cursor = 'grab';
            document.removeEventListener('pointermove', onPointerMove);
            document.removeEventListener('pointerup', onPointerUp);

            notifShade.style.transition = '';
            notifShade.style.transform = '';
            notifShade.style.opacity = '';
            notifShade.style.pointerEvents = '';

            const isOpen = notifShade.classList.contains('is-open');

            if (!hasMoved) {
                // Click/tap toggle
                notifShade.classList.toggle('is-open');
            } else {
                if (!isOpen) {
                    if (currentDrag > 28) {
                        notifShade.classList.add('is-open');
                    } else {
                        notifShade.classList.remove('is-open');
                    }
                } else {
                    if (currentDrag < -20) {
                        notifShade.classList.remove('is-open');
                    } else {
                        notifShade.classList.add('is-open');
                    }
                }
            }
        }

        statusBar.addEventListener('pointerdown', onPointerDown);

        const notifCard = notifShade.querySelector('.js-notif-card');
        if (notifCard) {
            notifCard.addEventListener('pointerdown', (e) => {
                if (e.target.closest('button')) return;
                onPointerDown(e);
            });
        }
    }

    if (notifDismiss && notifShade) {
        notifDismiss.addEventListener('click', (e) => {
            e.stopPropagation();
            notifShade.classList.remove('is-open');
            if (helpBtn) helpBtn.classList.remove('is-on');
        });

        document.addEventListener('pointerdown', (e) => {
            if (!notifShade.classList.contains('is-open')) return;
            if (!e.target.closest('.js-phone-notif-shade') && !e.target.closest('.js-phone-status-bar') && !e.target.closest('.js-mobile-help-btn')) {
                notifShade.classList.remove('is-open');
                if (helpBtn) helpBtn.classList.remove('is-on');
            }
        });
    }

    if (notifMuteBtn) {
        notifMuteBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            OwlmicStore.toggleMic();
        });
    }

    if (notifStatusBtn) {
        notifStatusBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            const current = OwlmicStore.getState().connection;
            if (current === 'connected') OwlmicStore.setConnectionState('approving');
            else if (current === 'approving') OwlmicStore.setConnectionState('reconnecting');
            else OwlmicStore.setConnectionState('connected');
        });
    }

    // Elements
    const cameraTile = root.querySelector('.js-mobile-camera');
    const cameraCanvas = root.querySelector('.js-mobile-camera-canvas');
    const cameraOffFace = root.querySelector('.js-mobile-camera-off');
    const cameraOverlay = root.querySelector('.js-mobile-camera-overlay');
    const flipBtn = root.querySelector('.js-mobile-flip');
    const cameraCaption = root.querySelector('.js-mobile-camera-caption');
    const playgroundWatermark = root.querySelector('.js-playground-watermark');

    const micTile = root.querySelector('.js-mobile-mic');
    const micState = root.querySelector('.js-mobile-mic-state');
    const micMeter = root.querySelector('.js-mobile-mic-meter');

    const speakerTile = root.querySelector('.js-mobile-speaker');
    const speakerState = root.querySelector('.js-mobile-speaker-state');
    const speakerDetail = root.querySelector('.js-mobile-speaker-detail');

    const statusTile = root.querySelector('.js-mobile-status-tile');
    const statusPc = root.querySelector('.js-mobile-status-pc');
    const statusText = root.querySelector('.js-mobile-status-text');
    const indicatorIcon = root.querySelector('.js-mobile-indicator-icon');
    const helpBtn = root.querySelector('.js-mobile-help-btn');

    // Register canvas with central renderer
    if (cameraCanvas) {
        cameraCanvas.width = 375;
        cameraCanvas.height = 440;
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

    // Status/WiFi tile tap toggles on/off exclusively (and cycles state if turned on)
    if (statusTile) {
        statusTile.addEventListener('click', (e) => {
            e.stopPropagation();
            const willBeOn = !statusTile.classList.contains('is-on');
            // Exclusive toggle: one can be turned on at a time
            if (helpBtn) helpBtn.classList.remove('is-on');
            if (notifShade) notifShade.classList.remove('is-open');

            if (willBeOn) {
                statusTile.classList.add('is-on');
            } else {
                statusTile.classList.remove('is-on');
            }

            // Cycle connection state
            const current = OwlmicStore.getState().connection;
            if (current === 'connected') OwlmicStore.setConnectionState('approving');
            else if (current === 'approving') OwlmicStore.setConnectionState('reconnecting');
            else if (current === 'reconnecting') OwlmicStore.setConnectionState('not_connected');
            else OwlmicStore.setConnectionState('connected');
        });
    }

    if (helpBtn) {
        helpBtn.addEventListener('click', (e) => {
            e.stopPropagation();
            const willBeOn = !helpBtn.classList.contains('is-on');
            // Exclusive toggle: one can be turned on at a time
            if (statusTile) statusTile.classList.remove('is-on');
            if (willBeOn) {
                helpBtn.classList.add('is-on');
            } else {
                helpBtn.classList.remove('is-on');
            }
        });
    }

    // State subscriber
    OwlmicStore.subscribe((state) => {
        // Camera button & preview
        if (cameraTile) {
            if (state.camera && (state.connection === 'connected' || state.connection === 'repair')) {
                cameraTile.classList.add('is-on');
                if (cameraCanvas) cameraCanvas.style.display = 'block';
                if (cameraOverlay) cameraOverlay.style.display = 'flex';
                if (cameraOffFace) cameraOffFace.style.display = 'none';
                if (playgroundWatermark) playgroundWatermark.style.display = 'none';
                if (cameraCaption) cameraCaption.textContent = `${state.cameraQuality} · ${state.cameraFps} fps`;
            } else {
                cameraTile.classList.remove('is-on');
                if (cameraCanvas) cameraCanvas.style.display = 'none';
                if (cameraOverlay) cameraOverlay.style.display = 'none';
                if (cameraOffFace) cameraOffFace.style.display = 'flex';
                if (playgroundWatermark) playgroundWatermark.style.display = 'flex';
            }
        }

        // Mic button
        if (micTile) {
            if (state.mic && state.connection === 'connected') {
                micTile.classList.add('is-on');
            } else {
                micTile.classList.remove('is-on');
            }
            if (micState) micState.textContent = state.mic ? 'On' : 'Off';
            if (micMeter) {
                micMeter.style.display = state.mic ? 'block' : 'none';
                micMeter.style.width = Math.min(100, Math.max(0, state.audioLevel * 100)) + '%';
            }
        }

        // Speaker button
        if (speakerTile) {
            if (state.speaker && state.connection === 'connected') {
                speakerTile.classList.add('is-on');
            } else {
                speakerTile.classList.remove('is-on');
            }
            if (speakerState) speakerState.textContent = state.speaker ? 'On' : 'Off';
            if (speakerDetail) {
                speakerDetail.style.display = state.speaker ? 'block' : 'none';
                speakerDetail.textContent = state.speakerOutput;
            }
        }

        if (statusPc) statusPc.textContent = state.pcName;
        if (statusText) statusText.textContent = state.connection === 'connected' ? 'Connected' : state.connection;

        // Notification card content updates
        if (notifTitle && notifDesc) {
            if (state.connection !== 'connected') {
                notifTitle.textContent = state.connection === 'approving' 
                    ? 'Security: Approval Required' 
                    : state.connection === 'reconnecting' 
                    ? 'Reconnecting to PC...' 
                    : 'Owlmic Service Disconnected';
                notifDesc.textContent = state.connection === 'approving'
                    ? `PC '${state.pcName}' is requesting connection code ${state.code}`
                    : `Link: ${state.link.replace('_', ' ')} · Tap to retry`;
                if (notifMuteBtn) notifMuteBtn.textContent = 'Retry Link';
            } else if (state.mic && state.camera) {
                notifTitle.textContent = 'Mic & Camera Streaming';
                notifDesc.textContent = `Streaming 48kHz audio + ${state.cameraQuality} video to ${state.pcName}`;
                if (notifMuteBtn) notifMuteBtn.textContent = 'Mute Mic';
            } else if (state.mic) {
                notifTitle.textContent = 'Microphone Streaming Active';
                notifDesc.textContent = `Streaming 48kHz PCM audio to ${state.pcName} over Wi-Fi`;
                if (notifMuteBtn) notifMuteBtn.textContent = 'Mute Mic';
            } else if (state.camera) {
                notifTitle.textContent = 'Virtual Camera Streaming';
                notifDesc.textContent = `Streaming ${state.cameraQuality} ${state.cameraFps}fps video to ${state.pcName}`;
                if (notifMuteBtn) notifMuteBtn.textContent = 'Start Mic';
            } else {
                notifTitle.textContent = 'Owlmic Service Standby';
                notifDesc.textContent = `Connected to ${state.pcName} · Ready to stream`;
                if (notifMuteBtn) notifMuteBtn.textContent = 'Start Mic';
            }
        }
    });
});
