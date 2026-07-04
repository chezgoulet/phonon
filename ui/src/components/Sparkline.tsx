import { useEffect, useRef } from "react";

interface Props {
  data: number[];
  color: string; // stroke color (hex or css color)
  /** Fixed value range. When omitted, scales to the data's own min/max. */
  min?: number;
  max?: number;
  width?: number;
  height?: number;
  className?: string;
}

/**
 * Minimal Canvas sparkline — no charting library. Draws a filled line for the
 * given series, scaled to [min, max] (or the data range). Handles HiDPI via
 * devicePixelRatio. Intended for small inline health trends.
 */
export default function Sparkline({
  data,
  color,
  min,
  max,
  width = 160,
  height = 36,
  className,
}: Props) {
  const ref = useRef<HTMLCanvasElement>(null);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d");
    if (!ctx) return;

    const dpr = window.devicePixelRatio || 1;
    canvas.width = width * dpr;
    canvas.height = height * dpr;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, width, height);

    if (data.length === 0) return;

    const lo = min ?? Math.min(...data);
    const hi = max ?? Math.max(...data);
    const range = hi - lo || 1; // avoid divide-by-zero on flat series
    const pad = 2;
    const usableH = height - pad * 2;
    const stepX = data.length > 1 ? width / (data.length - 1) : 0;

    const x = (i: number) => i * stepX;
    const y = (v: number) => {
      const clamped = Math.max(lo, Math.min(hi, v));
      return pad + usableH * (1 - (clamped - lo) / range);
    };

    // Single point: draw a dot so the trend area isn't blank.
    if (data.length === 1) {
      ctx.fillStyle = color;
      ctx.beginPath();
      ctx.arc(width / 2, y(data[0]), 2, 0, Math.PI * 2);
      ctx.fill();
      return;
    }

    // Fill under the line.
    ctx.beginPath();
    ctx.moveTo(x(0), height);
    data.forEach((v, i) => ctx.lineTo(x(i), y(v)));
    ctx.lineTo(x(data.length - 1), height);
    ctx.closePath();
    ctx.fillStyle = color + "22"; // ~13% alpha
    ctx.fill();

    // Stroke the line.
    ctx.beginPath();
    data.forEach((v, i) => (i === 0 ? ctx.moveTo(x(i), y(v)) : ctx.lineTo(x(i), y(v))));
    ctx.strokeStyle = color;
    ctx.lineWidth = 1.5;
    ctx.lineJoin = "round";
    ctx.stroke();
  }, [data, color, min, max, width, height]);

  return <canvas ref={ref} style={{ width, height }} className={className} />;
}
