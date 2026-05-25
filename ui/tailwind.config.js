/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        phonon: {
          bg: "#0f172a",
          card: "#1e293b",
          border: "#334155",
          text: "#f1f5f9",
          muted: "#94a3b8",
          accent: "#38bdf8",
          success: "#22c55e",
          warning: "#eab308",
          danger: "#ef4444",
        },
      },
    },
  },
  plugins: [],
};
