// Section 6 — Exact 5 tool definitions for Claude tool-use.
// NO OTHER TOOLS MAY BE ADDED.

export const AGENT_TOOLS = [
  {
    name: "draft_contract",
    description:
      "Given deal details in natural language, generate structured contract terms including delivery date, deposit percent, cancellation policy, and late-payment terms. Returns contract text to be stored in Deal.contractText.",
    input_schema: {
      type: "object",
      properties: {
        description: { type: "string", description: "Raw natural-language deal description from seller" },
        price: { type: "number", description: "Total price in paise" },
        dueDate: { type: "string", description: "ISO8601 delivery/due date" },
        customerName: { type: "string", description: "Customer name" },
      },
      required: ["description", "price", "dueDate", "customerName"],
    },
  },
  {
    name: "create_deposit_link",
    description:
      "Create a Razorpay Payment Link for the deposit amount. Must be called together with draft_contract for every new deal. Returns payment link URL and razorpay payment ID.",
    input_schema: {
      type: "object",
      properties: {
        dealId: { type: "string", description: "Deal ID from database" },
        amount: { type: "number", description: "Deposit amount in paise" },
        customerName: { type: "string" },
        description: { type: "string" },
      },
      required: ["dealId", "amount", "customerName", "description"],
    },
  },
  {
    name: "create_cancellation_fee_link",
    description:
      "Create a Razorpay Payment Link for a cancellation fee. Called when seller marks a deal as cancelled after work has begun.",
    input_schema: {
      type: "object",
      properties: {
        dealId: { type: "string" },
        percent: { type: "number", description: "Cancellation fee as percent of total price (e.g. 50)" },
        totalPrice: { type: "number", description: "Total deal price in paise" },
        customerName: { type: "string" },
      },
      required: ["dealId", "percent", "totalPrice", "customerName"],
    },
  },
  {
    name: "schedule_reminder",
    description:
      "Write a Reminder row to the database scheduling a follow-up message for the deal due date.",
    input_schema: {
      type: "object",
      properties: {
        dealId: { type: "string" },
        when: { type: "string", description: "ISO8601 date when reminder should fire" },
        channel: { type: "string", enum: ["email", "whatsapp"], description: "Delivery channel" },
        message: { type: "string", description: "Draft reminder message text" },
      },
      required: ["dealId", "when", "channel", "message"],
    },
  },
  {
    name: "send_reminder",
    description:
      "Send a previously scheduled reminder via email (Resend). Sets Reminder.sentAt timestamp.",
    input_schema: {
      type: "object",
      properties: {
        reminderId: { type: "string" },
      },
      required: ["reminderId"],
    },
  },
] as const
