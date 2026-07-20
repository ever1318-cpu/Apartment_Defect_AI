# Registry Specification

Registry format version은 `1.0`이다.

## Layout

```text
model-registry/
  registry.json
  registry.json.bak
  models/<model-name>/<model-version>/<package files>
  locks/registry.lock
```

등록 시 package는 registry 소유 immutable copy로 복사한다. 외부 package를 참조하지 않는다.

## Entry

Entry는 model name/version, stage, canonical relative package path, checksum digest,
등록/수정/승격 시각, source training run, dataset version, model metadata,
validation/compatibility 상태와 notes를 저장한다.

## Stage

- `development`: 초기 등록과 개발 검증
- `staging`: 배포 전 후보 또는 교체된 production
- `production`: model name당 하나의 활성 version
- `archived`: 비활성 보존

새 version을 production으로 승격하면 기존 production은 기본 staging, 선택적으로
archived로 이동한다. 한 mutation에서 revision은 1 증가한다.

## Concurrency와 recovery

Writer는 exclusive lock file을 사용한다. 기본 lock wait는 5초, stale 기준은 300초다.
`expected_revision`을 사용하면 optimistic concurrency conflict를 검출할 수 있다.
Index는 atomic write하고 backup을 갱신한다. Primary JSON이 손상되면 valid backup으로
복구하며 둘 다 손상되면 `RegistryCorruptionError`를 발생시킨다.

## Security

Model name/version은 단일 안전 path component여야 한다. Package path는
`models/<name>/<version>`만 허용한다. 등록 전/후 symlink tree와 strict package
validation을 검사한다. Copy 또는 index mutation 실패 시 부분 package를 rollback한다.
