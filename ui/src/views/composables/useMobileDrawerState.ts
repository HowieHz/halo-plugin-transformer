import { computed, onBeforeUnmount, onMounted, ref } from "vue";

export type MobileDrawerSide = "left" | "right";
export type MobileDrawerState = MobileDrawerSide | "none";
export const COMPACT_LAYOUT_BREAKPOINT_PX = 1300;
export const COMPACT_LAYOUT_MEDIA_QUERY = `(width < ${COMPACT_LAYOUT_BREAKPOINT_PX}px)`;

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
    /**
     * why: `1300px` 是控制台三栏切窄布局的统一断点。
     * 这里和样式层必须保持同一条规则，否则会出现 JS 已按抽屉逻辑运行，
     * 但 CSS 仍停留在桌面三栏，或者反过来的布局撕裂。
     */
    mediaQueryList = window.matchMedia(COMPACT_LAYOUT_MEDIA_QUERY);
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
