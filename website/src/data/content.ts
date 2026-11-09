import generated from '../../.generated/content.json';
import type { SiteContent } from './model';
import type { ImageMetadata } from 'astro';
export const content = generated as SiteContent;
const images = import.meta.glob<{ default: ImageMetadata }>(
  '../../.generated/assets/*.{png,jpg,jpeg,webp}',
  { eager: true },
);
const videos = import.meta.glob<{ default: string }>('../../.generated/assets/*.mp4', {
  eager: true,
  query: '?url',
});
export function appImage(name: keyof SiteContent['media']): ImageMetadata | undefined {
  const file = content.media[name]?.split('/').at(-1);
  if (file) return Object.entries(images).find(([path]) => path.endsWith('/' + file))?.[1].default;
  return undefined;
}
export function appVideo(name: keyof SiteContent['videos']): string | undefined {
  const file = content.videos[name]?.split('/').at(-1);
  const url = file
    ? Object.entries(videos).find(([path]) => path.endsWith('/' + file))?.[1].default
    : undefined;
  // Astro restricts direct navigation to root files; Vite serves this path in development.
  return import.meta.env.DEV && url ? `/@fs${content.videos[name]}` : url;
}
export const dateLabel = (date: string) =>
  new Intl.DateTimeFormat('en', { dateStyle: 'long', timeZone: 'UTC' }).format(new Date(date));
