// Owlmic Prototype Sandbox - Core State Store & Engine
// Shared by Mobile, Mobile Horizontal, and PC views

(function() {
    'use strict';

    // Application state matching Owlmic architecture
    const state = {
        connection: 'connected', // 'connected' | 'approving' | 'reconnecting' | 'not_connected' | 'repair'
        link: 'wifi',            // 'wifi' | 'usb_debugging' | 'usb_tethering' | 'bluetooth'
        phoneName: 'Pixel 8',
        pcName: 'Workstation',
        code: '4821',
        camera: false,
        cameraQuality: '1080p',
        cameraFps: 30,
        cameraLens: 'Back',      // 'Back' | 'Front'
        cameraFraming: 'Fill',   // 'Fill' | 'Fit'
        cameraMirror: false,
        cameraOrientation: 'Auto',
        mic: false,
        micBoost: 'Off',
        micNoise: 'Phone',
        audioLevel: 0.0,
        speaker: false,
        speakerOutput: 'Phone speaker',
        pcScreen: 'main',        // 'main' | 'gate' | 'settings'
        mobileSettingsOpen: false,
        repairing: false,
        pairedPhones: [
            { id: 'pixel-8', name: 'Pixel 8', blocked: false, current: true }
        ],
        addresses: ['192.168.1.15', '10.0.0.42']
    };

    const listeners = new Set();

    function notify() {
        listeners.forEach(fn => fn(state));
    }

    // Audio level meter simulation loop
    let audioTimer = null;
    let targetAudio = 0;
    function updateAudio() {
        if (state.mic && state.connection === 'connected') {
            if (Math.random() < 0.15) {
                targetAudio = Math.random() * 0.85 + 0.1;
            } else if (Math.random() < 0.2) {
                targetAudio = 0.05;
            }
            state.audioLevel += (targetAudio - state.audioLevel) * 0.25;
        } else {
            state.audioLevel = 0.0;
        }
        notify();
        requestAnimationFrame(updateAudio);
    }
    requestAnimationFrame(updateAudio);

    // Camera canvas renderer
    const registeredCanvases = new Set();

    function registerCameraCanvas(canvas) {
        registeredCanvases.add(canvas);
    }

    function unregisterCameraCanvas(canvas) {
        registeredCanvases.delete(canvas);
    }

    let frameCount = 0;
    function renderCameras() {
        frameCount++;
        if (state.camera && (state.connection === 'connected' || state.connection === 'repair')) {
            const time = frameCount * 0.03;
            registeredCanvases.forEach(canvas => {
                if (!canvas.isConnected) return;
                const ctx = canvas.getContext('2d');
                const w = canvas.width;
                const h = canvas.height;

                // Base dark camera background
                ctx.fillStyle = '#0a0a0a';
                ctx.fillRect(0, 0, w, h);

                // Preserved dotted background pattern (matching phone playground)
                const dotSpacing = 18;
                ctx.fillStyle = 'rgba(255, 255, 255, 0.12)';
                for (let dx = dotSpacing / 2; dx < w; dx += dotSpacing) {
                    for (let dy = dotSpacing / 2; dy < h; dy += dotSpacing) {
                        ctx.beginPath();
                        ctx.arc(dx, dy, 1, 0, Math.PI * 2);
                        ctx.fill();
                    }
                }

                // Check device mobile orientation if canvas is inside phone
                const isPhoneCanvas = canvas.classList.contains('js-mobile-camera-canvas');
                const deviceMobile = document.querySelector('.js-device-mobile');
                const isLandscape = isPhoneCanvas && deviceMobile && deviceMobile.classList.contains('is-landscape');

                // Draw clean person silhouette
                ctx.save();
                ctx.translate(w * 0.5, h * 0.5);
                if (isLandscape) {
                    ctx.rotate(Math.PI / 2); // 90deg rotation to remain upright when phone rotates -90deg
                }

                // Smooth organic breathing / drift
                const drift = Math.sin(time * 0.4) * 3;
                const headRadius = isLandscape ? Math.min(w, h) * 0.15 : Math.min(w, h) * 0.17;
                const headCenterY = -headRadius * 0.7 + drift;

                // Minimal clean man silhouette color
                ctx.fillStyle = '#1e1f26';

                // Head
                ctx.beginPath();
                ctx.arc(0, headCenterY, headRadius, 0, Math.PI * 2);
                ctx.fill();

                // Shoulders & Torso
                const shoulderY = headCenterY + headRadius * 1.35;
                const shoulderHalfWidth = headRadius * 1.9;
                const torsoHeight = headRadius * 2.2;

                ctx.beginPath();
                ctx.ellipse(0, shoulderY + torsoHeight * 0.5, shoulderHalfWidth, torsoHeight, 0, 0, Math.PI * 2);
                ctx.fill();

                ctx.restore();
            });
        }
        requestAnimationFrame(renderCameras);
    }
    requestAnimationFrame(renderCameras);

    // Global store actions
    window.OwlmicStore = {
        getState: () => ({ ...state }),
        subscribe: (fn) => {
            listeners.add(fn);
            fn(state);
            return () => listeners.delete(fn);
        },
        toggleCamera: () => {
            if (state.connection !== 'connected') return;
            state.camera = !state.camera;
            notify();
        },
        toggleMic: () => {
            if (state.connection !== 'connected') return;
            state.mic = !state.mic;
            notify();
        },
        toggleSpeaker: () => {
            if (state.connection !== 'connected') return;
            state.speaker = !state.speaker;
            notify();
        },
        flipLens: () => {
            state.cameraLens = state.cameraLens === 'Back' ? 'Front' : 'Back';
            notify();
        },
        cycleFraming: () => {
            state.cameraFraming = state.cameraFraming === 'Fill' ? 'Fit' : 'Fill';
            notify();
        },
        toggleMirror: () => {
            state.cameraMirror = !state.cameraMirror;
            notify();
        },
        cycleQuality: () => {
            const qualities = ['Auto', '1080p', '720p'];
            const idx = qualities.indexOf(state.cameraQuality);
            state.cameraQuality = qualities[(idx + 1) % qualities.length];
            notify();
        },
        cycleFps: () => {
            const fpsList = [24, 30, 60];
            const idx = fpsList.indexOf(state.cameraFps);
            state.cameraFps = fpsList[(idx + 1) % fpsList.length];
            notify();
        },
        cycleNoiseReduction: () => {
            const opts = ['Phone', 'Off'];
            state.micNoise = state.micNoise === 'Phone' ? 'Off' : 'Phone';
            notify();
        },
        cycleBoost: () => {
            const opts = ['Off', '+6 dB', '+12 dB'];
            const idx = opts.indexOf(state.micBoost);
            state.micBoost = opts[(idx + 1) % opts.length];
            notify();
        },
        setConnectionState: (cState) => {
            state.connection = cState;
            if (cState === 'approving') {
                state.pcScreen = 'gate';
                state.camera = false;
                state.mic = false;
                state.speaker = false;
            } else if (cState === 'not_connected') {
                state.pcScreen = 'main';
                state.camera = false;
                state.mic = false;
                state.speaker = false;
            } else if (cState === 'repair') {
                state.pcScreen = 'main';
            } else {
                state.pcScreen = 'main';
            }
            notify();
        },
        setLinkKind: (link) => {
            state.link = link;
            notify();
        },
        openMobileSettings: (open) => {
            state.mobileSettingsOpen = open;
            notify();
        },
        setPcScreen: (scr) => {
            state.pcScreen = scr;
            notify();
        },
        allowApproval: () => {
            state.connection = 'connected';
            state.pcScreen = 'main';
            notify();
        },
        denyApproval: () => {
            state.connection = 'not_connected';
            state.pcScreen = 'main';
            notify();
        },
        repairNow: () => {
            state.repairing = true;
            notify();
            setTimeout(() => {
                state.repairing = false;
                state.connection = 'connected';
                notify();
            }, 1200);
        },
        registerCameraCanvas,
        unregisterCameraCanvas
    };
})();
