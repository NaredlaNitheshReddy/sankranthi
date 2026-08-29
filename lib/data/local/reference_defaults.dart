/// The reference rows a fresh install starts with.
///
/// These are the values that used to be enum constants. Seeding them at
/// migration 1 means a brand-new device is usable offline immediately (§108),
/// before any download has happened, and that downloaded reference data is an
/// *override* rather than a prerequisite.
///
/// Slugs must not change: they are the wire values, and existing records point
/// at them. Labels, ordering and the active flag are all safe to change, here
/// or by an admin at runtime.
library;

/// A reference row to seed, independent of drift so it can be unit-tested and
/// reused by the gateway seeding script.
class ReferenceSeed {
  const ReferenceSeed({
    required this.key,
    required this.label,
    this.symbol,
    this.decimals,
    this.sign,
    this.requiresDirection,
    this.requiresNote,
    this.sortOrder = 100,
  });

  final String key;
  final String label;
  final String? symbol;
  final int? decimals;
  final int? sign;
  final bool? requiresDirection;
  final bool? requiresNote;
  final int sortOrder;
}

/// Expense categories (§27), in the order they appear in a picker.
const List<ReferenceSeed> defaultExpenseCategories = <ReferenceSeed>[
  ReferenceSeed(key: 'feed', label: 'Feed', sortOrder: 10),
  ReferenceSeed(key: 'veterinary', label: 'Veterinary', sortOrder: 20),
  ReferenceSeed(key: 'labour', label: 'Labour', sortOrder: 30),
  ReferenceSeed(key: 'transport', label: 'Transport', sortOrder: 40),
  ReferenceSeed(key: 'shed_repair', label: 'Shed repair', sortOrder: 50),
  ReferenceSeed(key: 'utilities', label: 'Utilities', sortOrder: 60),
  // Last on purpose, and the fallback an unrecognised category maps to.
  ReferenceSeed(key: 'other', label: 'Other', sortOrder: 900),
];

/// The category every expense falls back to when its own is missing.
const String fallbackExpenseCategoryKey = 'other';

/// Stock units (§32). `decimals` is display precision, capped at the stored
/// resolution of thousandths.
const List<ReferenceSeed> defaultStockUnits = <ReferenceSeed>[
  ReferenceSeed(
    key: 'kilogram',
    label: 'Kilogram',
    symbol: 'kg',
    decimals: 3,
    sortOrder: 10,
  ),
  ReferenceSeed(
    key: 'gram',
    label: 'Gram',
    symbol: 'g',
    decimals: 0,
    sortOrder: 20,
  ),
  ReferenceSeed(
    key: 'litre',
    label: 'Litre',
    symbol: 'L',
    decimals: 3,
    sortOrder: 30,
  ),
  ReferenceSeed(
    key: 'millilitre',
    label: 'Millilitre',
    symbol: 'mL',
    decimals: 0,
    sortOrder: 40,
  ),
  // Countable things show no decimals: there is no third of a sack.
  ReferenceSeed(
    key: 'piece',
    label: 'Piece',
    symbol: 'pcs',
    decimals: 0,
    sortOrder: 50,
  ),
  ReferenceSeed(
    key: 'bag',
    label: 'Bag',
    symbol: 'bags',
    decimals: 0,
    sortOrder: 60,
  ),
  ReferenceSeed(
    key: 'bundle',
    label: 'Bundle',
    symbol: 'bundles',
    decimals: 0,
    sortOrder: 70,
  ),
  ReferenceSeed(
    key: 'dose',
    label: 'Dose',
    symbol: 'doses',
    decimals: 0,
    sortOrder: 80,
  ),
];

/// Stock movement types (§32).
///
/// `sign` is what the balance sum uses. `requiresDirection` marks the types
/// where the user chooses which way the movement goes, so the stored quantity
/// already carries its sign.
const List<ReferenceSeed> defaultStockTxnTypes = <ReferenceSeed>[
  ReferenceSeed(
    key: 'purchase',
    label: 'Purchase',
    sign: 1,
    requiresDirection: false,
    sortOrder: 10,
  ),
  ReferenceSeed(
    key: 'consumption',
    label: 'Consumption',
    sign: -1,
    requiresDirection: false,
    sortOrder: 20,
  ),
  ReferenceSeed(
    key: 'adjustment',
    label: 'Adjustment',
    sign: 0,
    requiresDirection: true,
    sortOrder: 30,
  ),
  ReferenceSeed(
    key: 'transfer',
    label: 'Transfer',
    sign: 0,
    requiresDirection: true,
    sortOrder: 40,
  ),
];

/// Why a livestock count changed (§33, §44).
const List<ReferenceSeed> defaultCountReasons = <ReferenceSeed>[
  ReferenceSeed(key: 'birth', label: 'Birth', sign: 1, sortOrder: 10),
  ReferenceSeed(key: 'death', label: 'Death', sign: -1, sortOrder: 20),
  ReferenceSeed(key: 'purchase', label: 'Purchased', sign: 1, sortOrder: 30),
  ReferenceSeed(key: 'sale', label: 'Sold', sign: -1, sortOrder: 40),
  ReferenceSeed(
    key: 'transfer_in',
    label: 'Transferred in',
    sign: 1,
    sortOrder: 50,
  ),
  ReferenceSeed(
    key: 'transfer_out',
    label: 'Transferred out',
    sign: -1,
    sortOrder: 60,
  ),
  // The one reason that does not explain itself, so it demands a note.
  ReferenceSeed(
    key: 'correction',
    label: 'Correction',
    sign: 0,
    requiresNote: true,
    sortOrder: 900,
  ),
];

/// Livestock categories (§33). A starting point the organisation will edit.
const List<ReferenceSeed> defaultLivestockCategories = <ReferenceSeed>[
  ReferenceSeed(key: 'goat', label: 'Goat', sortOrder: 10),
  ReferenceSeed(key: 'sheep', label: 'Sheep', sortOrder: 20),
  ReferenceSeed(key: 'cow', label: 'Cow', sortOrder: 30),
  ReferenceSeed(key: 'buffalo', label: 'Buffalo', sortOrder: 40),
  ReferenceSeed(key: 'poultry', label: 'Poultry', sortOrder: 50),
];
