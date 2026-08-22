import NextAuth from "next-auth"
import EmailProvider from "next-auth/providers/email"
import { PrismaAdapter } from "@auth/prisma-adapter"
import { prisma } from "@/lib/prisma"

const handler = NextAuth({
  adapter: PrismaAdapter(prisma) as any,
  providers: [
    EmailProvider({
      server: {
        host: process.env.EMAIL_SERVER_HOST || "smtp.resend.com",
        port: Number(process.env.EMAIL_SERVER_PORT || 465),
        auth: {
          user: process.env.EMAIL_SERVER_USER || "resend",
          pass: process.env.EMAIL_SERVER_PASSWORD || "",
        },
      },
      from: process.env.EMAIL_FROM || "noreply@sellerprotection.app",
    }),
  ],
  callbacks: {
    async session({ session, user }) {
      const seller = await prisma.seller.findUnique({ where: { userId: user.id } })
      if (seller) {
        (session.user as any).sellerId = seller.id
      }
      (session.user as any).id = user.id
      return session
    },
    async signIn({ user }) {
      if (!user.email) return false
      const existing = await prisma.seller.findUnique({ where: { email: user.email } })
      if (!existing && user.id) {
        await prisma.seller.create({
          data: {
            email: user.email,
            name: user.name ?? user.email.split("@")[0],
            userId: user.id,
          },
        })
      }
      return true
    },
  },
  pages: {
    signIn: "/auth/signin",
  },
  secret: process.env.NEXTAUTH_SECRET,
})

export { handler as GET, handler as POST }