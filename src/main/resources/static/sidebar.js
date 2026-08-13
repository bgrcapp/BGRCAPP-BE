(function initializeSidebar() {
    const sidebar = document.getElementById('appSidebar');
    const toggle = document.getElementById('appSidebarToggle');
    const backdrop = document.getElementById('appSidebarBackdrop');
    if (!sidebar || !toggle) return;

    const desktopMedia = window.matchMedia('(min-width: 70rem)');
    const savedState = localStorage.getItem('attendance.sidebar.open');
    const initialOpen = savedState === null ? desktopMedia.matches : savedState === 'true';

    function setOpen(open) {
        document.body.classList.toggle('sidebar-open', open);
        toggle.setAttribute('aria-expanded', String(open));
        toggle.setAttribute('aria-label', open ? '좌측 패널 닫기' : '좌측 패널 열기');
        toggle.title = open ? '좌측 패널 닫기' : '좌측 패널 열기';
        toggle.innerHTML = `<i class="fa-solid ${open ? 'fa-xmark' : 'fa-bars'}" aria-hidden="true"></i>`;
        localStorage.setItem('attendance.sidebar.open', String(open));
    }

    setOpen(initialOpen);
    // HTML 파싱 단계에서 적용한 초기 상태를 실제 body 상태로 넘긴다.
    document.documentElement.classList.remove('sidebar-initial-open');
    requestAnimationFrame(() => document.body.classList.add('sidebar-ready'));
    toggle.addEventListener('click', () => setOpen(!document.body.classList.contains('sidebar-open')));
    backdrop?.addEventListener('click', () => setOpen(false));
    document.addEventListener('keydown', (event) => {
        if (event.key === 'Escape' && document.body.classList.contains('sidebar-open')) setOpen(false);
    });
    desktopMedia.addEventListener('change', (event) => {
        if (localStorage.getItem('attendance.sidebar.open') === null) setOpen(event.matches);
    });

})();
