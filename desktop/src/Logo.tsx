/** OpenScreen mark: a phone casting to a display (same artwork as the app icons). */
export function Logo({ size = 32 }: { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 108 108" aria-hidden="true">
      <rect x="0" y="0" width="108" height="108" rx="24" fill="var(--brand)" />
      <rect x="29" y="34" width="50" height="34" rx="5" fill="none" stroke="#fff" strokeWidth="4" />
      <rect x="46" y="72" width="16" height="4" fill="#fff" />
      <rect x="24" y="48" width="18" height="30" rx="3" fill="#fff" />
      <rect x="28" y="52" width="10" height="20" fill="var(--brand)" />
      <path
        d="M48 56a8 8 0 0 1 8 8"
        fill="none"
        stroke="#fff"
        strokeWidth="3"
        strokeLinecap="round"
      />
      <path
        d="M48 47a17 17 0 0 1 17 17"
        fill="none"
        stroke="#fff"
        strokeWidth="3"
        strokeLinecap="round"
      />
    </svg>
  );
}
