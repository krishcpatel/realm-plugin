# Realm SMP Plugin

A modular Minecraft server plugin powering the **Realm SMP economy and server systems**.

Realm is designed around a **clean modular architecture** where the core infrastructure provides shared services and independent modules implement gameplay systems such as economy, clans, jobs, and land ownership.

The plugin focuses on **transaction safety, extensibility, and server economy stability**.

Java Documentation can be found here: https://krishcpatel.github.io/realm-plugin/javadoc/

---

# Current Features

## Core Infrastructure

- Player database management
- Modular plugin system
- Internal event bus
- Configuration loading
- Database abstraction
- Server command framework

## Economy System

- Persistent player bank accounts
- Transaction-safe money transfers
- Ledger system for auditing all money flow
- Admin economy commands
- Player-to-player payments

## Bank Note Currency

Players can convert digital bank money into **physical tradable items**.

Features:

- withdraw money as bank notes
- redeem bank notes
- trade notes between players
- shift + right-click to redeem notes
- notes stored in database
- fraud prevention (notes cannot be redeemed twice)

## Ledger / Transaction Monitoring

Every economy action is recorded.

Examples include:

- player payments
- admin actions
- bank note withdrawals
- bank note redemption
- future systems (shops, jobs, upkeep)

This allows:

- debugging economy issues
- detecting exploits
- financial transparency

---

# Player Commands

## Balance

```
/bal
/balance
```

Shows the player's bank balance.

---

## Withdraw Bank Note

```
/withdraw <amount>
```

Converts bank money into a physical bank note item.

Example:

```
/withdraw 500
```

Player receives a **$500 bank note item**.

---

## Redeem Bank Note

```
/redeem
```

Redeems the bank note held in the player's hand.

Alternatively:

```
Shift + Right Click with the note
```

---

## Player Payments

```
/pay <player> <amount>
```

Transfers money between players.

Example:

```
/pay Steve 100
```

---

# Admin Commands

## Give Money

```
/eco give <player> <amount>
```

Adds money to a player's bank account.

---

## Take Money

```
/eco take <player> <amount>
```

Removes money from a player's bank account.

---

## Set Balance

```
/eco set <player> <amount>
```

Sets a player's bank balance.

---

## Ledger Inspection

```
/ledger <player> [limit]
```

Shows recent transactions for a player.

Example:

```
/ledger Steve 10
```

---

# Bank Notes

Bank notes are **physical currency items**.

They behave similarly to systems like **Hypixel Skyblock**.

Properties:

- stored as item
- unique note ID
- recorded in database
- tradable
- redeemable anywhere
- cannot be redeemed twice

Example note:

```
Bank Note
Value: $500
Note ID: a3f92c1d
```

Players can:

- trade notes
- store notes in chests
- drop notes
- redeem notes

---

# Plugin Architecture

Realm is built using a **layered modular architecture**.

```
Core Infrastructure
│
├── ConfigManager
├── DatabaseManager
├── EventSystem
├── PlayerRepository
├── Module System
│
└── Modules
    │
    └── Economy Module
        ├── EconomyRepository
        ├── TransactionManager
        ├── LedgerRepository
        ├── BankNoteRepository
        ├── BankNoteManager
        └── Commands
```

---

# Core Systems

## DatabaseManager

Handles database connections.

Currently uses:

```
SQLite
```

Future support planned for:

```
MySQL / MariaDB
PostgreSQL
```

---

## Event System

Realm contains an **internal event bus**.

Modules can publish and subscribe to events without direct dependencies.

Example events:

```
PlayerUpsertedEvent
LedgerRecordedEvent
```

This enables modules like:

- jobs
- shops
- clans

to respond to economic activity.

---

# Economy Architecture

The economy is designed around a **central transaction gateway**.

```
Command
   ↓
TransactionManager
   ↓
EconomyRepository
   ↓
LedgerRepository
```

This ensures:

- atomic money changes
- ledger recording
- exploit prevention

---

# Transaction Types

Examples of ledger entries:

```
PAY
ADMIN_GIVE
ADMIN_TAKE
NOTE_WITHDRAW
NOTE_REDEEM
```

Future entries:

```
SHOP_PURCHASE
JOB_REWARD
CLAN_UPKEEP
PLOT_RENT
```

---

# API for Modules

Other modules interact with the economy through **TransactionManager**.

Example usage:

```
transactionManager.transfer(
    fromUuid,
    toUuid,
    amount,
    MoneySource.SHOP,
    reference,
    reason,
    actor
);
```

This ensures:

- money cannot go negative
- ledger entries are created
- economy remains consistent

---

# Creating Money (Mint)

```
transactionManager.mint(
    playerUuid,
    amount,
    MoneySource.JOBS,
    reference,
    reason,
    actor
);
```

Example:

```
Jobs plugin paying player
```

---

# Removing Money (Burn)

```
transactionManager.burn(
    playerUuid,
    amount,
    MoneySource.UPKEEP,
    reference,
    reason,
    actor
);
```

Example:

```
Clan upkeep payment
```

---

# Planned Systems

## Jobs System

Players earn money through work.

Examples:

- mining
- farming
- crafting
- fishing

Payments use the **transaction system**.

---

## Shop System

Player-run shops using bank or note currency.

Possible features:

- chest shops
- sign shops
- marketplace stalls

---

## Clan System

Teams with shared upgrades and upkeep.

Possible features:

- team vault
- warps
- land claims
- clan progression

---

## Land / Plot System

Players rent land or plots.

Example:

```
weekly upkeep
plot taxes
```

---

## Economy Sinks

Money removal systems to prevent inflation.

Examples:

```
teleport costs
auction listing fees
land rent
clan upkeep
repair costs
```

---

# Future Improvements

## Bank NPCs

Players interact with banker NPCs to:

- withdraw notes
- deposit notes
- check balances

---

## Economy Analytics

Admin monitoring tools:

- economy inflation tracking
- player wealth leaderboard
- suspicious transaction detection

---

# Development Goals

Realm aims to provide:

- stable SMP economy
- extensible plugin architecture
- secure transaction system
- clear developer API

---

# License

Private project for **Realm SMP server infrastructure**.
