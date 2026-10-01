---
name: api-design
description: REST API design guide for new endpoints — RESTful URL structure, DTO-only arguments for controllers and services, how query parameters bind, OpenAPI documentation, and response format. Examples for Spring and NestJS; the principles apply to any stack.
---

# REST API Design Guide

The rules below are about the shape of the API, not about one framework. Each one is shown in Spring
(Kotlin) and NestJS (TypeScript) — **read the example matching this project** and ignore the other.
If it's neither, keep the principle and use the project's own binding syntax.

## URL Design

- RESTful principles: `/v1/auth/api-keys`
- Use plural: `/students`, `/clubs`
- Hierarchy: `/students/{id}/projects`

## Everything Crosses a Boundary as a DTO

Controllers and services never take loose positional parameters. Every argument that crosses a boundary
is a DTO, and every response is a DTO.

```kotlin
// Spring — request body → ReqDto
@PostMapping("/api-keys")
fun create(@Valid @RequestBody reqDto: CreateApiKeyReqDto): ApiKeyResDto

// query parameters → @ModelAttribute ReqDto, not a pile of @RequestParam
@GetMapping("/students")
fun query(@Valid @ModelAttribute reqDto: QueryStudentReqDto): List<StudentResDto>

// service takes the same DTO — not (name, grade, status, page, size)
fun query(reqDto: QueryStudentReqDto): List<StudentResDto>
```

```typescript
// NestJS — request body → ReqDto (ValidationPipe validates it)
@Post('api-keys')
create(@Body() reqDto: CreateApiKeyReqDto): Promise<ApiKeyResDto>

// query parameters → a single @Query() DTO, not several @Query('name') scalars
@Get('students')
query(@Query() reqDto: QueryStudentReqDto): Promise<StudentResDto[]>

// service takes the same DTO — not (name, grade, status, page, size)
query(reqDto: QueryStudentReqDto): Promise<StudentResDto[]>
```

Why: adding a field doesn't ripple through every signature, argument order can't be mixed up, and
validation rules live next to the shape they describe.

### One Scalar vs. a DTO

- **A DTO is the default for query parameters.** It is also what makes validation possible on them —
  Spring needs `@ModelAttribute` + `@Valid`, NestJS needs `@Query()` on a class with `ValidationPipe`.
- **A single scalar binding** (`@RequestParam` / `@Query('force')`) is for one self-contained value that
  will never grow, e.g. `?force=true`. Two or more parameters means a DTO.
- Path variables (`@PathVariable` / `@Param`) stay primitives — they're part of the URL, not a payload.
- Query and path values arrive as **strings**. Convert them explicitly where the stack requires it, and
  be careful with booleans: a naive string→boolean conversion makes `?force=false` come out `true`.

## Query Parameters

- Filtering: `?status=active`
- Pagination: `?page=0&size=20`
- Sorting: `?sort=createdAt,desc`

## OpenAPI Documentation

Document each endpoint with whatever the project already uses — `springdoc` annotations on Spring,
`@nestjs/swagger` decorators on NestJS:

```kotlin
@Operation(summary = "Create API key", description = "...")
@ApiResponse(responseCode = "200", description = "Success")
@PostMapping("/api-keys")
fun create(@Valid @RequestBody reqDto: CreateApiKeyReqDto): ApiKeyResDto
```

```typescript
@ApiOperation({ summary: 'Create API key' })
@ApiResponse({ status: 201, type: ApiKeyResDto })
@Post('api-keys')
create(@Body() reqDto: CreateApiKeyReqDto): Promise<ApiKeyResDto>
```

## Response Format

- Success: return the `ResDto` directly — no envelope/wrapper type. The HTTP status carries the outcome.
- Error: throw a domain exception → the global exception handler (Spring `@RestControllerAdvice`,
  NestJS exception filter) turns it into the error body.

Don't wrap successful payloads in a `data` field. Clients read the resource straight from the body, so an
envelope only adds a layer to unwrap on every call.
