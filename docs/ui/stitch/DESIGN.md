---
name: Orbita SE Lifecycle Workspace
colors:
  surface: '#f8f9fa'
  surface-dim: '#d9dadb'
  surface-bright: '#f8f9fa'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f3f4f5'
  surface-container: '#edeeef'
  surface-container-high: '#e7e8e9'
  surface-container-highest: '#e1e3e4'
  on-surface: '#191c1d'
  on-surface-variant: '#44474c'
  inverse-surface: '#2e3132'
  inverse-on-surface: '#f0f1f2'
  outline: '#74777d'
  outline-variant: '#c4c6cc'
  surface-tint: '#525f71'
  primary: '#000000'
  on-primary: '#ffffff'
  primary-container: '#0f1c2c'
  on-primary-container: '#778598'
  inverse-primary: '#bac8dc'
  secondary: '#115cb9'
  on-secondary: '#ffffff'
  secondary-container: '#659dfe'
  on-secondary-container: '#003370'
  tertiary: '#000000'
  on-tertiary: '#ffffff'
  tertiary-container: '#281804'
  on-tertiary-container: '#9a7f61'
  error: '#ba1a1a'
  on-error: '#ffffff'
  error-container: '#ffdad6'
  on-error-container: '#93000a'
  primary-fixed: '#d6e4f9'
  primary-fixed-dim: '#bac8dc'
  on-primary-fixed: '#0f1c2c'
  on-primary-fixed-variant: '#3a4859'
  secondary-fixed: '#d7e2ff'
  secondary-fixed-dim: '#acc7ff'
  on-secondary-fixed: '#001a40'
  on-secondary-fixed-variant: '#004491'
  tertiary-fixed: '#feddba'
  tertiary-fixed-dim: '#e0c1a0'
  on-tertiary-fixed: '#281804'
  on-tertiary-fixed-variant: '#584329'
  background: '#f8f9fa'
  on-background: '#191c1d'
  surface-variant: '#e1e3e4'
typography:
  headline-lg:
    fontFamily: Inter
    fontSize: 24px
    fontWeight: '600'
    lineHeight: 32px
    letterSpacing: -0.02em
  headline-md:
    fontFamily: Inter
    fontSize: 18px
    fontWeight: '600'
    lineHeight: 24px
    letterSpacing: -0.01em
  body-md:
    fontFamily: Inter
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
  body-sm:
    fontFamily: Inter
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
  label-caps:
    fontFamily: Inter
    fontSize: 11px
    fontWeight: '700'
    lineHeight: 16px
    letterSpacing: 0.05em
  code-sm:
    fontFamily: JetBrains Mono
    fontSize: 12px
    fontWeight: '400'
    lineHeight: 16px
rounded:
  sm: 0.125rem
  DEFAULT: 0.25rem
  md: 0.375rem
  lg: 0.5rem
  xl: 0.75rem
  full: 9999px
spacing:
  global-nav-width: 64px
  lifecycle-rail-width: 240px
  context-panel-width: 320px
  gutter: 1px
  padding-dense: 4px
  padding-standard: 8px
  padding-md: 16px
---

## Brand & Style
The design system is engineered for high-stakes systems engineering and lifecycle management. The brand personality is rooted in precision, technical rigor, and industrial reliability. It targets professional engineers and project managers who require a workspace that prioritizes data integrity and cognitive efficiency over visual flair.

The design style is **Corporate / Modern** with a lean toward **Minimalism** to accommodate extreme information density. It utilizes a restrained aesthetic where every pixel serves a functional purpose, ensuring that complex hierarchical data remains legible and actionable. The interface is designed to evoke a sense of professional stability and systematic order, facilitating deep work in complex technical environments.

## Colors
The palette is architected for prolonged focus and clear information hierarchy.
- **Primary (Global Navigation):** Dark Navy (#0D1B2A) provides a grounding frame for the application, clearly separating global controls from the workspace.
- **Surface (Workspace):** Light Engineering Gray (#F8F9FA) reduces eye strain compared to pure white while maintaining a clean, professional canvas.
- **Action (Professional Blue):** Used sparingly for primary actions, active states, and selection focus.
- **Status Semantic Palette:** Highly legible colors for critical engineering states. Success (Green), Warning (Amber), Danger/Blocking (Red), and Stale (Gray) are used strictly for data status and alerts to avoid visual noise.

## Typography
This design system uses **Inter** for all UI elements to ensure maximum legibility of the Russian Cyrillic alphabet at small sizes. **JetBrains Mono** is utilized for technical identifiers, requirement IDs, and metadata where character-level distinction is critical.

- **Russian Language Optimization:** Line heights are slightly increased for body text to accommodate the vertical complexity of Cyrillic glyphs.
- **Density:** Body-sm (12px) is the primary size for table data and tree nodes to maximize vertical information display.
- **Identifiers:** Technical tags and IDs use monospaced fonts to ensure they are easily distinguishable from descriptive text.

## Layout & Spacing
The system uses a **Four-Zone Layout** model optimized for wide-screen monitors. 
- **Global Nav:** A slim left-docked rail for high-level app switching.
- **Lifecycle Rail:** A secondary navigation area for hierarchical tree structures (Requirements, Architecture).
- **Main Workspace:** A fluid area for heavy data manipulation (Tables, Matrices).
- **Context Inspector:** A right-docked collapsible panel for property editing.

The spacing rhythm follows a strict 4px base unit. In data-heavy views, 4px (dense) padding is preferred. The "Gutter" is defined as 1px, typically manifested as hairline borders (#DEE2E6) to separate zones without consuming excessive space.

## Elevation & Depth
Elevation is conveyed through **Low-contrast outlines** and **Tonal layers** rather than shadows. This minimizes visual clutter in dense grids.
- **Level 0 (Background):** #F8F9FA.
- **Level 1 (Panels/Cards):** #FFFFFF with 1px border (#DEE2E6).
- **Level 2 (Modals/Popovers):** #FFFFFF with a subtle, tight shadow (0 2px 8px rgba(0,0,0,0.1)) to provide clear separation from the workspace.
- **Active State:** Elements in focus use a 2px Professional Blue (#0056B3) left-edge accent or a subtle background tint (#E7F1FF).

## Shapes
The design system adopts a **Soft (0.25rem)** roundedness profile for primary components like buttons and input fields to maintain a professional yet modern feel. However, for structural layout elements like panels, data rows, and workspace tabs, a **Sharp (0px)** or near-sharp approach is used to maximize the alignment with the grid and emphasize the technical, systematic nature of the tool.

## Components
- **Data Tables:** High-density grids with 32px row heights. Use alternating row stripes (zebra striping) at very low opacity. Headers are sticky and use `label-caps` typography. Row-level actions appear on hover to reduce permanent visual noise.
- **Hierarchical Trees:** Use chevron icons for expansion. Indentation is strictly 16px per level. Connective vertical lines are used to clarify deep nesting.
- **Lifecycle Rail:** Vertical progress indicators with status pips. Active phases use a solid blue background; completed phases use a checkmark icon.
- **Input Fields:** Rectangular with 1px borders. Focused inputs use the secondary blue for the border color. Labels are consistently placed above the input in `body-sm` weight 600.
- **Matrix Views:** Square cells with color-coded status indicators. Use "frozen" first columns and headers for orientation.
- **Context Inspector:** Vertical stack of collapsible sections. Each section header is a light gray bar (#E9ECEF) to clearly divide property groups.