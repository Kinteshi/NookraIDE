import { useEffect, useState } from "react";

type AdaptiveLayout = {
  compact: boolean;
  touchPrimary: boolean;
};

function readLayout(): AdaptiveLayout {
  return {
    compact: window.innerWidth < 768,
    touchPrimary: window.matchMedia("(pointer: coarse)").matches,
  };
}

export function useAdaptiveLayout(): AdaptiveLayout {
  const [layout, setLayout] = useState(readLayout);

  useEffect(() => {
    const pointer = window.matchMedia("(pointer: coarse)");
    const update = () => setLayout(readLayout());
    window.addEventListener("resize", update);
    window.addEventListener("orientationchange", update);
    pointer.addEventListener("change", update);
    return () => {
      window.removeEventListener("resize", update);
      window.removeEventListener("orientationchange", update);
      pointer.removeEventListener("change", update);
    };
  }, []);

  return layout;
}

