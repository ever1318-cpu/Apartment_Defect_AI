# Architecture Diagram

```mermaid
flowchart LR
  subgraph Field["Field Data"]
    A[Raw images] --> B[Ingestion]
    B --> C[Quality & duplicates]
    C --> D[Privacy derivatives]
    D --> E[Labeling tasks]
    E --> F[Annotation QA]
    F --> G[Dataset version]
  end
  subgraph ML["Model Lifecycle"]
    G --> H[Training dataset]
    H --> I[TrainingBackend]
    I --> J[ONNX export]
    J --> K[Model package]
    K --> L[Registry]
  end
  subgraph Runtime["Runtime"]
    L --> M[VisionBackend]
    M --> N[Inference & evaluation]
    L --> O[FastAPI serving]
    O --> P[Metrics & caches]
    L --> Q[Release check]
  end
```

Text fallback:

```text
Raw → Ingest → Curate → Label → QA → Version
    → Train → ONNX → Package → Registry
    → Inference / Serving / Evaluation / Release Check
```

## Trust boundaries

```mermaid
flowchart TB
  U[Untrusted field files] -->|signature, traversal, symlink checks| I[Owned ingestion batch]
  I -->|privacy approval| D[Training derivative]
  D -->|schema + QA| T[Training artifacts]
  T -->|checksum + compatibility| P[Validated package]
  P -->|immutable copy + revision lock| R[Registry]
  R -->|production stage| S[Serving process]
  S -->|size, MIME, JSON, concurrency limits| C[Client response]
```

상세 설명은 [Architecture.md](Architecture.md)를 참고한다.
