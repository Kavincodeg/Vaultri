import { NextAuthOptions } from 'next-auth'
import CredentialsProvider from 'next-auth/providers/credentials'
import { prisma } from '@/lib/prisma'
import bcrypt from 'bcryptjs'

export const authOptions: NextAuthOptions = {
  session: { strategy: 'jwt' },
  providers: [
    CredentialsProvider({
      name: 'Credentials',
      credentials: {
        email: { label: 'Email', type: 'email' },
        password: { label: 'Password', type: 'password' },
      },
      async authorize(credentials) {
        if (!credentials?.email || !credentials?.password) return null
        const seller = await prisma.seller.findUnique({ where: { email: credentials.email } })
        if (!seller) return null
        const valid = await bcrypt.compare(credentials.password, seller.passwordHash)
        if (!valid) return null
        return { id: seller.id, email: seller.email, name: seller.name }
      },
    }),
  ],
  callbacks: {
    async session({ session, token }) {
      if (session.user && token.sub) {
        (session.user as Record<string, unknown>).sellerId = token.sub
      }
      return session
    },
    async jwt({ token, user }) {
      if (user) { token.sub = user.id }
      return token
    },
  },
  pages: { signIn: '/auth/signin' },
  secret: process.env.NEXTAUTH_SECRET,
}