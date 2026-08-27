import type { Metadata } from "next";
import "./globals.css";

export const metadata: Metadata = {
  metadataBase: new URL("https://chanriva.shinp-studio.com/"),
  title: "ちゃんりば | CHANRIVA — ちゃんとリバーシ",
  description: "対局・棋譜レビューから、外部盤面の取り込み、Edaxによる全合法手比較、理論指標を使った探求、傾向分析まで。リバーシ研究環境、ちゃんりば。",
  alternates: { canonical: "https://chanriva.shinp-studio.com/" },
  icons: { icon: "/images/app-icon.png", shortcut: "/images/app-icon.png" },
  openGraph: { title: "ちゃんりば | CHANRIVA", description: "対局を、もっと深く。局面を取り込み、Edax評価と理論指標から掘り下げるリバーシ研究環境。", url: "https://chanriva.shinp-studio.com/", siteName: "ちゃんりば", locale: "ja_JP", type: "website", images: [{ url: "/images/app-icon.png", width: 877, height: 877, alt: "ちゃんりば CHANRIVA" }] },
  twitter: { card: "summary_large_image", title: "ちゃんりば | CHANRIVA", description: "対局・検討・理論探求・傾向分析まで。強くなりたいプレイヤーのためのリバーシ。", images: ["/images/app-icon.png"] },
  themeColor: "#05060b",
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="ja"><body>{children}</body></html>;
}
