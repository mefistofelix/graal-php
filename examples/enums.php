<?php
declare(strict_types=1);

enum Status: string {
    case Ready = 'ready';
    case Running = 'running';
    case Done = 'done';

    public function label(): string {
        return match ($this) {
            self::Ready => 'Ready to run',
            self::Running => 'Running',
            self::Done => 'Completed',
        };
    }
}

class Job {
    public Status $status = Status::Ready;
    public $payload = ['name' => 'demo'];
}

$job = new Job;
$copy = clone $job;
$copy->status = Status::from('done');
$copy->payload['name'][0] = 'D';

echo $job->status->label(), ':', $job->payload['name'], "\n";
echo $copy->status->label(), ':', $copy->payload['name'], "\n";
echo count(Status::cases()), ':', Status::tryFrom('unknown') === null, "\n";
