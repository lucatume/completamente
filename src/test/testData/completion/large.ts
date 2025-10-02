// Comprehensive TypeScript test file with various indentation levels
// This file is designed to test how the llama.vim plugin selects prefix/middle/suffix

interface DataItem {
    id: number;
    name: string;
    value: number;
}

function greeting(): string {
    return "Hello, World!";
}

function factorial(n: number): number {
    if (n <= 1) {
        return 1;
    }
    return n * factorial(n - 1);
}

function sum(a: number, b: number): number {
    return a + b;
}

function multiply(x: number, y: number): number {
    const result = x * y;
    if (result > 1000) {
        console.log("Result is large:", result);
    }
    return result;
}

class Calculator {
    private value: number = 0;

    constructor(initialValue: number = 0) {
        this.value = initialValue;
    }

    add(n: number): void {
        this.value += n;
    }

    subtract(n: number): void {
        this.value -= n;
    }

    getResult(): number {
        return this.value;
    }
}

async function processArray(items: DataItem[]): Promise<number[]> {
    const results: number[] = [];

    for (const item of items) {
        if (item.value > 0) {
            const processed = item.value * 2;
            results.push(processed);
            console.log("Processed:", item.name);
        }
    }

    return results;
}

function filterData(data: DataItem[], threshold: number): DataItem[] {
    return data.filter(item => {
        if (item.value >= threshold) {
            return true;
        }
        return false;
    });
}

const mapData = (items: DataItem[]): Map<number, string> => {
    const result = new Map<number, string>();

    for (const item of items) {
        if (!result.has(item.id)) {
            result.set(item.id, item.name);
        }
    }

    return result;
};

interface Config {
    debug: boolean;
    timeout: number;
    retries: number;
}

class Service {
    private config: Config;
    private cache: Map<string, any> = new Map();

    constructor(config: Config) {
        this.config = config;
        this.initializeCache();
    }

    private initializeCache(): void {
        if (this.config.debug) {
            console.log("Initializing cache");
        }
        // Cache initialization logic
    }

    async execute(query: string): Promise<any> {
        if (this.cache.has(query)) {
            return this.cache.get(query);
        }

        try {
            const result = await this.performQuery(query);
            this.cache.set(query, result);
            return result;
        } catch (error) {
            if (this.config.retries > 0) {
                return this.execute(query);
            }
            throw error;
        }
    }

    private async performQuery(query: string): Promise<any> {
        // Query execution logic
        return { status: "success", data: [] };
    }
}

enum Status {
    PENDING = "PENDING",
    IN_PROGRESS = "IN_PROGRESS",
    COMPLETED = "COMPLETED",
    FAILED = "FAILED"
}

type Task = {
    id: string;
    name: string;
    status: Status;
    dependencies: string[];
};

function executeTasks(tasks: Task[]): void {
    const completed = new Set<string>();
    const queue = tasks.filter(t => t.dependencies.length === 0);

    while (queue.length > 0) {
        const task = queue.shift();
        if (!task) break;

        console.log(`Executing: ${task.name}`);
        completed.add(task.id);

        const remaining = tasks.filter(t =>
            !completed.has(t.id) &&
            t.dependencies.every(dep => completed.has(dep))
        );

        queue.push(...remaining);
    }
}

// Additional utility functions and classes for larger test file

abstract class BaseRepository<T> {
    protected cache: Map<string, T> = new Map();

    abstract fetch(id: string): Promise<T>;

    async getOrFetch(id: string): Promise<T> {
        if (this.cache.has(id)) {
            return this.cache.get(id)!;
        }
        const item = await this.fetch(id);
        this.cache.set(id, item);
        return item;
    }

    clear(): void {
        this.cache.clear();
    }
}

interface Logger {
    log(message: string): void;
    error(message: string): void;
    warn(message: string): void;
}

class ConsoleLogger implements Logger {
    log(message: string): void {
        console.log(`[LOG] ${message}`);
    }

    error(message: string): void {
        console.error(`[ERROR] ${message}`);
    }

    warn(message: string): void {
        console.warn(`[WARN] ${message}`);
    }
}

interface RequestOptions {
    method: string;
    headers?: Record<string, string>;
    body?: string;
    timeout?: number;
}

class HttpClient {
    private logger: Logger;

    constructor(logger: Logger) {
        this.logger = logger;
    }

    async request<T>(url: string, options: RequestOptions): Promise<T> {
        try {
            this.logger.log(`Making request to ${url}`);
            const response = await fetch(url, {
                method: options.method,
                headers: options.headers,
                body: options.body,
            });

            if (!response.ok) {
                throw new Error(`HTTP error! status: ${response.status}`);
            }

            const data: T = await response.json();
            this.logger.log(`Request successful`);
            return data;
        } catch (error) {
            this.logger.error(`Request failed: ${error}`);
            throw error;
        }
    }
}

interface StateAction<T> {
    type: string;
    payload?: T;
}

class StateManager<T> {
    private state: T;
    private listeners: Array<(state: T) => void> = [];

    constructor(initialState: T) {
        this.state = initialState;
    }

    getState(): T {
        return this.state;
    }

    setState(newState: T): void {
        this.state = newState;
        this.notifyListeners();
    }

    subscribe(listener: (state: T) => void): () => void {
        this.listeners.push(listener);
        return () => {
            this.listeners = this.listeners.filter(l => l !== listener);
        };
    }

    private notifyListeners(): void {
        this.listeners.forEach(listener => listener(this.state));
    }
}

type ValidationRule<T> = (value: T) => boolean;
type ValidationError = { field: string; message: string };

class Validator<T> {
    private rules: Map<keyof T, ValidationRule<any>[]> = new Map();

    addRule<K extends keyof T>(field: K, rule: ValidationRule<T[K]>): this {
        if (!this.rules.has(field)) {
            this.rules.set(field, []);
        }
        this.rules.get(field)!.push(rule);
        return this;
    }

    validate(data: T): ValidationError[] {
        const errors: ValidationError[] = [];

        for (const [field, rules] of this.rules.entries()) {
            const value = data[field];
            for (const rule of rules) {
                if (!rule(value)) {
                    errors.push({
                        field: String(field),
                        message: `Validation failed for field ${String(field)}`,
                    });
                }
            }
        }

        return errors;
    }
}

interface PaginationParams {
    page: number;
    pageSize: number;
    sortBy?: string;
}

interface PaginatedResult<T> {
    items: T[];
    total: number;
    page: number;
    pageSize: number;
    totalPages: number;
}

class PaginationHelper {
    static paginate<T>(
        items: T[],
        params: PaginationParams
    ): PaginatedResult<T> {
        const start = (params.page - 1) * params.pageSize;
        const end = start + params.pageSize;
        const paginatedItems = items.slice(start, end);

        return {
            items: paginatedItems,
            total: items.length,
            page: params.page,
            pageSize: params.pageSize,
            totalPages: Math.ceil(items.length / params.pageSize),
        };
    }
}

interface CacheConfig {
    ttl: number;
    maxSize: number;
}

class CacheManager<T> {
    private cache: Map<string, { value: T; expiry: number }> = new Map();
    private config: CacheConfig;

    constructor(config: CacheConfig) {
        this.config = config;
    }

    set(key: string, value: T): void {
        if (this.cache.size >= this.config.maxSize) {
            const firstKey = this.cache.keys().next().value;
            this.cache.delete(firstKey);
        }

        const expiry = Date.now() + this.config.ttl;
        this.cache.set(key, { value, expiry });
    }

    get(key: string): T | null {
        const item = this.cache.get(key);
        if (!item) return null;

        if (Date.now() > item.expiry) {
            this.cache.delete(key);
            return null;
        }

        return item.value;
    }

    clear(): void {
        this.cache.clear();
    }

    size(): number {
        return this.cache.size;
    }
}

interface EventHandler<T> {
    (event: T): void;
}

class EventEmitter<T> {
    private handlers: Map<string, EventHandler<T>[]> = new Map();

    on(event: string, handler: EventHandler<T>): void {
        if (!this.handlers.has(event)) {
            this.handlers.set(event, []);
        }
        this.handlers.get(event)!.push(handler);
    }

    off(event: string, handler: EventHandler<T>): void {
        const handlers = this.handlers.get(event);
        if (handlers) {
            const index = handlers.indexOf(handler);
            if (index > -1) {
                handlers.splice(index, 1);
            }
        }
    }

    emit(event: string, data: T): void {
        const handlers = this.handlers.get(event);
        if (handlers) {
            handlers.forEach(handler => handler(data));
        }
    }

    clear(): void {
        this.handlers.clear();
    }
}

// Export all new classes and interfaces
export {
    greeting,
    Calculator,
    Service,
    executeTasks,
    BaseRepository,
    ConsoleLogger,
    HttpClient,
    StateManager,
    Validator,
    PaginationHelper,
    CacheManager,
    EventEmitter,
};

export type {
    DataItem,
    Config,
    Task,
    Logger,
    RequestOptions,
    StateAction,
    ValidationRule,
    ValidationError,
    PaginationParams,
    PaginatedResult,
    CacheConfig,
    EventHandler,
};
