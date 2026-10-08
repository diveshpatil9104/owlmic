// Tab Switching Logic
document.addEventListener('DOMContentLoaded', () => {
    const tabBtns = document.querySelectorAll('.tab-btn');
    const views = document.querySelectorAll('.view');
    const indicator = document.querySelector('.tab-indicator');

    function updateIndicator(activeBtn) {
        if (!indicator || !activeBtn) return;
        // Calculate exact horizontal position relative to the tab-bar container
        const left = activeBtn.offsetLeft;
        const width = activeBtn.offsetWidth;
        
        indicator.style.transform = `translateX(${left}px)`;
        indicator.style.width = `${width}px`;
    }

    // Set initial position
    const activeBtn = document.querySelector('.tab-btn.active');
    updateIndicator(activeBtn);

    tabBtns.forEach(btn => {
        btn.addEventListener('click', () => {
            // Update active states on buttons
            tabBtns.forEach(b => b.classList.remove('active'));
            btn.classList.add('active');

            // Slide indicator
            updateIndicator(btn);

            // Hide all views
            views.forEach(v => {
                v.classList.remove('active');
            });

            // Show target view
            const targetId = btn.getAttribute('data-target');
            const targetView = document.getElementById(targetId);
            if (targetView) {
                targetView.classList.add('active');
            }
        });
    });

    // Update indicator width and position if window resizes
    window.addEventListener('resize', () => {
        const currentBtn = document.querySelector('.tab-btn.active');
        updateIndicator(currentBtn);
    });
});
