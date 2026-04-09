import { computed, onBeforeUnmount, onMounted, ref } from "vue";

export type MobileDrawerSide = "left" | "right";
export type MobileDrawerState = MobileDrawerSide | "none";

export function createMobileDrawerController() {
  const isMobileViewport = ref(false);
  const activeDrawer = ref<MobileDrawerState>("none");

  const showLeftDrawer = computed(() => isMobileViewport.value && activeDrawer.value === "left");
  const showRightDrawer = computed(() => isMobileViewport.value && activeDrawer.value === "right");
  const backdropVisible = computed(() => isMobileViewport.value && activeDrawer.value !== "none");

  /**
   * why: 抽屉状态只该在移动端生效；
   * 一旦回到桌面端，左右栏本来就是常驻区，不应继续残留“某个抽屉还开着”的隐藏状态。
   */
  function syncViewport(isMobile: boolean) {
    isMobileViewport.value = isMobile;
    if (!isMobile) {
      activeDrawer.value = "none";
    }
  }

  function closeDrawer() {
    activeDrawer.value = "none";
  }

  function openDrawer(side: MobileDrawerSide) {
    if (!isMobileViewport.value) {
      return;
    }
    activeDrawer.value = side;
  }

  function toggleDrawer(side: MobileDrawerSide) {
    if (!isMobileViewport.value) {
      return;
    }
    activeDrawer.value = activeDrawer.value === side ? "none" : side;
  }

  return {
    activeDrawer,
    backdropVisible,
    closeDrawer,
    isMobileViewport,
    openDrawer,
    showLeftDrawer,
    showRightDrawer,
    syncViewport,
    toggleDrawer,
  };
}

export function useMobileDrawerState() {
  const controller = createMobileDrawerController();
  let mediaQueryList: MediaQueryList | null = null;
  let handleChange: ((event: MediaQueryListEvent) => void) | null = null;

  onMounted(() => {
    if (typeof window === "undefined" || typeof window.matchMedia !== "function") {
      return;
    }
    mediaQueryList = window.matchMedia("(width < 1250px)");
    controller.syncViewport(mediaQueryList.matches);

    handleChange = (event) => {
      controller.syncViewport(event.matches);
    };

    mediaQueryList.addEventListener("change", handleChange);
  });

  onBeforeUnmount(() => {
    if (!mediaQueryList || !handleChange) {
      return;
    }
    mediaQueryList.removeEventListener("change", handleChange);
  });

  return controller;
}
