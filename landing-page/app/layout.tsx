import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  metadataBase: new URL("https://chanriva.shinp-studio.com/"),
  title: "ちゃんりば | CHANRIVA — ちゃんとリバーシ",
  description: "対局・検討・傾向分析まで。強くなりたいプレイヤーのためのリバーシ体験、ちゃんりば。",
  alternates: { canonical: "https://chanriva.shinp-studio.com/" },
  icons: { icon: "/images/app-icon.png", shortcut: "/images/app-icon.png" },
  openGraph: { title: "ちゃんりば | CHANRIVA", description: "対局を、もっと深く。対局・検討・傾向分析まで。", url: "https://chanriva.shinp-studio.com/", siteName: "ちゃんりば", locale: "ja_JP", type: "website", images: [{ url: "/images/app-icon.png", width: 877, height: 877, alt: "ちゃんりば CHANRIVA" }] },
  twitter: { card: "summary_large_image", title: "ちゃんりば | CHANRIVA", description: "対局・検討・傾向分析まで。強くなりたいプレイヤーのためのリバーシ。", images: ["/images/app-icon.png"] },
  themeColor: "#05060b",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="ja"><body>{children}</body></html>;
}
