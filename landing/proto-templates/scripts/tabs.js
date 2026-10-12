// Tab Switching & Device Rotation Engine

document.addEventListener('DOMContentLoaded', () => {
    const tabBtns = document.querySelectorAll('.tab-btn');
    const indicator = document.querySelector('.tab-indicator');
    const viewPhone = document.getElementById('view-phone');
    const viewPc = document.getElementById('view-pc');
    const deviceMobile = document.querySelector('.js-device-mobile');

    function updateIndicator(activeBtn) {
        if (!indicator || !activeBtn) return;
        const left = activeBtn.offsetLeft;
        const width = activeBtn.offsetWidth;
        indicator.style.transform = `translateX(${left}px)`;
        indicator.style.width = `${width}px`;
    }

    function setMode(target) {
        tabBtns.forEach(btn => {
            if (btn.getAttribute('data-target') === target) {
                btn.classList.add('active');
                updateIndicator(btn);
            } else {
                btn.classList.remove('active');
            }
        });

        if (window._phoneRotationTimeout) {
            clearTimeout(window._phoneRotationTimeout);
            window._phoneRotationTimeout = null;
        }

        if (target === 'phone-portrait') {
            if (viewPc) viewPc.classList.remove('active');
            if (viewPhone) viewPhone.classList.add('active');
            if (deviceMobile) {
                // If already in portrait, do nothing
                const alreadyPortrait = deviceMobile.classList.contains('is-portrait-chassis') ||
                    (!deviceMobile.classList.contains('is-landscape-chassis') && deviceMobile.classList.contains('is-portrait'));

                if (!alreadyPortrait) {
                    // Start physical phone rotation back towards portrait (0deg)
                    deviceMobile.classList.remove('is-landscape-chassis');
                    deviceMobile.classList.add('is-portrait-chassis');

                    // For the first ~250ms (rotation from -90deg to -45deg), screen stays in landscape mode.
                    // At 45deg mark, suddenly snap everything into portrait mode!
                    window._phoneRotationTimeout = setTimeout(() => {
                        deviceMobile.classList.remove('is-landscape');
                        deviceMobile.classList.add('is-portrait');
                        window._phoneRotationTimeout = null;
                    }, 250);
                } else {
                    deviceMobile.classList.remove('is-landscape');
                    deviceMobile.classList.add('is-portrait');
                }
            }
        } else if (target === 'phone-landscape') {
            if (viewPc) viewPc.classList.remove('active');
            if (viewPhone) viewPhone.classList.add('active');
            if (deviceMobile) {
                // If already in landscape, do nothing
                const alreadyLandscape = deviceMobile.classList.contains('is-landscape-chassis') && deviceMobile.classList.contains('is-landscape');

                if (!alreadyLandscape) {
                    const notifShade = document.querySelector('.js-phone-notif-shade');
                    if (notifShade) notifShade.classList.remove('is-open');

                    // Start physical phone rotation towards landscape (-90deg)
                    deviceMobile.classList.remove('is-portrait-chassis');
                    deviceMobile.classList.add('is-landscape-chassis');

                    // For the first ~250ms (rotation from 0deg to -45deg), screen stays in portrait mode.
                    // At 45deg mark, suddenly snap everything into horizontal mode!
                    window._phoneRotationTimeout = setTimeout(() => {
                        deviceMobile.classList.remove('is-portrait');
                        deviceMobile.classList.add('is-landscape');
                        window._phoneRotationTimeout = null;
                    }, 250);
                } else {
                    const notifShade = document.querySelector('.js-phone-notif-shade');
                    if (notifShade) notifShade.classList.remove('is-open');
                    deviceMobile.classList.remove('is-portrait');
                    deviceMobile.classList.add('is-landscape');
                }
            }
        } else if (target === 'pc') {
            if (viewPhone) viewPhone.classList.remove('active');
            if (viewPc) viewPc.classList.add('active');
        }
    }

    // Set initial position
    const activeBtn = document.querySelector('.tab-btn.active');
    if (activeBtn) {
        updateIndicator(activeBtn);
    }

    tabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            const target = btn.getAttribute('data-target');
            setMode(target);
        });
    });

    window.addEventListener('resize', () => {
        const currentBtn = document.querySelector('.tab-btn.active');
        updateIndicator(currentBtn);
    });

    window.OwlmicNavigation = { setMode };
});
