export function rotateX([x, y, z], degrees) {
  const radians = degrees * Math.PI / 180;
  const cos = Math.cos(radians);
  const sin = Math.sin(radians);
  return [x, y * cos - z * sin, y * sin + z * cos];
}

export function rotateY([x, y, z], degrees) {
  const radians = degrees * Math.PI / 180;
  const cos = Math.cos(radians);
  const sin = Math.sin(radians);
  return [x * cos + z * sin, y, -x * sin + z * cos];
}

export function rotateZ([x, y, z], degrees) {
  const radians = degrees * Math.PI / 180;
  const cos = Math.cos(radians);
  const sin = Math.sin(radians);
  return [x * cos - y * sin, x * sin + y * cos, z];
}
