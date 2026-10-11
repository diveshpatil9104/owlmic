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
        mobileHorizontalSettingsOpen: false,
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

                // Base room background
                const grad = ctx.createLinearGradient(0, 0, w, h);
                if (state.cameraLens === 'Front') {
                    // Soft warm studio lighting
                    grad.addColorStop(0, '#1c1c24');
                    grad.addColorStop(0.5, '#121217');
                    grad.addColorStop(1, '#0a0a0f');
                } else {
                    // Back lens scene
                    grad.addColorStop(0, '#18201a');
                    grad.addColorStop(0.5, '#0f1412');
                    grad.addColorStop(1, '#080a09');
                }
                ctx.fillStyle = grad;
                ctx.fillRect(0, 0, w, h);

                // Subtle ambient bokeh / lighting orbs
                const x1 = w * 0.3 + Math.sin(time * 0.8) * (w * 0.05);
                const y1 = h * 0.4 + Math.cos(time * 0.6) * (h * 0.05);
                const r1 = Math.min(w, h) * 0.4;
                const lightGrad = ctx.createRadialGradient(x1, y1, 10, x1, y1, r1);
                lightGrad.addColorStop(0, state.cameraLens === 'Front' ? 'rgba(80, 100, 160, 0.18)' : 'rgba(70, 140, 100, 0.16)');
                lightGrad.addColorStop(1, 'rgba(0, 0, 0, 0)');
                ctx.fillStyle = lightGrad;
                ctx.fillRect(0, 0, w, h);

                // Subtle grid guidelines (rule of thirds)
                ctx.strokeStyle = 'rgba(255, 255, 255, 0.05)';
                ctx.lineWidth = 1;
                ctx.beginPath();
                ctx.moveTo(w * 0.333, 0); ctx.lineTo(w * 0.333, h);
                ctx.moveTo(w * 0.666, 0); ctx.lineTo(w * 0.666, h);
                ctx.moveTo(0, h * 0.333); ctx.lineTo(w, h * 0.333);
                ctx.moveTo(0, h * 0.666); ctx.lineTo(w, h * 0.666);
                ctx.stroke();

                // Stylized subject silhouette / focus point
                const cx = w * 0.5 + Math.sin(time * 0.4) * (w * 0.02);
                const cy = h * 0.58;
                const headR = Math.min(w, h) * 0.18;

                // Head
                ctx.fillStyle = '#22232a';
                ctx.beginPath();
                ctx.arc(cx, cy - headR * 0.8, headR, 0, Math.PI * 2);
                ctx.fill();

                // Shoulders
                ctx.beginPath();
                ctx.ellipse(cx, cy + headR * 1.3, headR * 2.2, headR * 1.5, 0, 0, Math.PI * 2);
                ctx.fill();

                // Focus reticle in center
                const reticleX = cx;
                const reticleY = cy - headR * 0.8;
                const rSize = 14;
                ctx.strokeStyle = 'rgba(255, 255, 255, 0.25)';
                ctx.lineWidth = 1.5;
                ctx.beginPath();
                // Corners
                ctx.moveTo(reticleX - rSize, reticleY - rSize + 4);
                ctx.lineTo(reticleX - rSize, reticleY - rSize);
                ctx.lineTo(reticleX - rSize + 4, reticleY - rSize);

                ctx.moveTo(reticleX + rSize - 4, reticleY - rSize);
                ctx.lineTo(reticleX + rSize, reticleY - rSize);
                ctx.lineTo(reticleX + rSize, reticleY - rSize + 4);

                ctx.moveTo(reticleX - rSize, reticleY + rSize - 4);
                ctx.lineTo(reticleX - rSize, reticleY + rSize);
                ctx.lineTo(reticleX - rSize + 4, reticleY + rSize);

                ctx.moveTo(reticleX + rSize - 4, reticleY + rSize);
                ctx.lineTo(reticleX + rSize, reticleY + rSize);
                ctx.lineTo(reticleX + rSize, reticleY + rSize - 4);
                ctx.stroke();

                // Small center green dot
                ctx.fillStyle = '#30D158';
                ctx.beginPath();
                ctx.arc(reticleX, reticleY, 2, 0, Math.PI * 2);
                ctx.fill();

                // Subtle film grain
                const grainCount = Math.floor((w * h) / 1200);
                ctx.fillStyle = 'rgba(255, 255, 255, 0.04)';
                for (let i = 0; i < grainCount; i++) {
                    const gx = Math.random() * w;
                    const gy = Math.random() * h;
                    ctx.fillRect(gx, gy, 1, 1);
                }
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
        openMobileHorizontalSettings: (open) => {
            state.mobileHorizontalSettingsOpen = open;
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
