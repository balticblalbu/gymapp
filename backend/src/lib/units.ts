export const LB_PER_KG = 2.2046226218;

export function lbToKg(lb: number): number {
  return lb / LB_PER_KG;
}

export function kgToLb(kg: number): number {
  return kg * LB_PER_KG;
}

export function kmToMeters(km: number): number {
  return km * 1000;
}

export function milesToMeters(miles: number): number {
  return miles * 1609.344;
}

/** Rounds to a sane precision for weights (0.25 kg steps are common in gyms). */
export function roundWeight(kg: number): number {
  return Math.round(kg * 100) / 100;
}

export function round(value: number, decimals = 1): number {
  const factor = 10 ** decimals;
  return Math.round(value * factor) / factor;
}
