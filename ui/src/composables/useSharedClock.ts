import { onBeforeUnmount, onMounted, ref } from "vue";

// The graph stays open for a long time and renders one footer per asset card: a single interval, shared by all of
// them and running only while at least one is mounted, so "2 min ago" does not freeze.
const REFRESH_MS = 60_000;
const now = ref(Date.now());
let timer: ReturnType<typeof setInterval> | undefined;
let subscribers = 0;

export function useSharedClock() {
    // The module-level value may be hours old when the first footer of a new graph renders.
    now.value = Date.now();
    onMounted(() => {
        if (subscribers++ === 0) {
            timer = setInterval(() => (now.value = Date.now()), REFRESH_MS);
        }
    });
    onBeforeUnmount(() => {
        if (--subscribers === 0) {
            clearInterval(timer);
            timer = undefined;
        }
    });
    return { now };
}