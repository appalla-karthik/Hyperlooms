package containerruntime

import (
	"context"
	"encoding/json"
	"errors"
	"reflect"
	"strings"
	"testing"
)

const testNodeID = "cafe0000-0000-4000-8000-000000000002"

type orphanFixture struct {
	id     string
	lease  string
	name   string
	labels map[string]string
}

func fixture(idByte string, lease string) orphanFixture {
	return orphanFixture{
		id: strings.Repeat(idByte, 64), lease: lease,
		name: "/hyperlooms-" + lease + "-0000000000000001",
		labels: map[string]string{
			managedLabelKey: managedLabelValue,
			nodeLabelKey:    testNodeID,
			leaseLabelKey:   lease,
		},
	}
}

func orphanDocker(fixtures []orphanFixture) *fakeDocker {
	fake := &fakeDocker{}
	fake.handle = func(args []string) (string, error) {
		switch {
		case len(args) == 7 && reflect.DeepEqual(args[:4], []string{"container", "ls", "--all", "--no-trunc"}):
			ids := make([]string, 0, len(fixtures))
			for _, item := range fixtures {
				ids = append(ids, item.id)
			}
			return strings.Join(ids, "\n"), nil
		case len(args) == 4 && args[0] == "container" && args[1] == "inspect":
			for _, item := range fixtures {
				if args[3] != item.id {
					continue
				}
				switch args[2] {
				case "--format={{json .Config.Labels}}":
					data, _ := json.Marshal(item.labels)
					return string(data), nil
				case "--format={{.Name}}":
					return item.name + "\n", nil
				}
			}
		case len(args) == 4 && reflect.DeepEqual(args[:3], []string{"container", "rm", "--force"}):
			return args[3], nil
		}
		return "", errors.New("unexpected Docker command")
	}
	return fake
}

func TestRecoveryVerifiesAllBeforeRemovingByExactID(t *testing.T) {
	first := fixture("a", testLeaseID)
	second := fixture("b", "cafe0000-0000-4000-8000-000000000003")
	fake := orphanDocker([]orphanFixture{first, second})
	count, err := recoverWith(context.Background(), testNodeID, fake)
	if err != nil || count != 2 {
		t.Fatalf("recovery count=%d err=%v", count, err)
	}
	list := fake.calls[0]
	for _, required := range []string{
		"--filter=label=" + managedLabelKey + "=" + managedLabelValue,
		"--filter=label=" + nodeLabelKey + "=" + testNodeID,
		"--format={{.ID}}", "--no-trunc", "--all",
	} {
		if !contains(list, required) {
			t.Fatalf("list omitted %q: %v", required, list)
		}
	}
	if len(fake.calls) != 7 || fake.calls[5][1] != "rm" || fake.calls[6][1] != "rm" ||
		fake.calls[5][3] != first.id || fake.calls[6][3] != second.id {
		t.Fatalf("unexpected verify/remove order: %v", fake.calls)
	}
}

func TestRecoveryFailsClosedOnOwnershipMismatch(t *testing.T) {
	tests := map[string]func(*orphanFixture){
		"foreign node":   func(item *orphanFixture) { item.labels[nodeLabelKey] = "cafe0000-0000-4000-8000-000000000004" },
		"missing marker": func(item *orphanFixture) { delete(item.labels, managedLabelKey) },
		"foreign name":   func(item *orphanFixture) { item.name = "/postgres" },
		"lease mismatch": func(item *orphanFixture) { item.labels[leaseLabelKey] = "cafe0000-0000-4000-8000-000000000005" },
	}
	for name, mutate := range tests {
		t.Run(name, func(t *testing.T) {
			first := fixture("a", testLeaseID)
			second := fixture("b", "cafe0000-0000-4000-8000-000000000003")
			mutate(&second)
			fake := orphanDocker([]orphanFixture{first, second})
			count, err := recoverWith(context.Background(), testNodeID, fake)
			if err == nil || count != 0 {
				t.Fatalf("expected closed recovery, count=%d err=%v", count, err)
			}
			for _, call := range fake.calls {
				if len(call) > 1 && call[1] == "rm" {
					t.Fatalf("removed before verifying whole set: %v", fake.calls)
				}
			}
		})
	}
}

func TestRecoveryRejectsInvalidListAndNodeWithoutRemoving(t *testing.T) {
	for _, item := range []orphanFixture{
		{id: "short"},
		fixture("a", testLeaseID),
	} {
		fixtures := []orphanFixture{item}
		if item.id != "short" {
			fixtures = append(fixtures, item) // Duplicate ID.
		}
		fake := orphanDocker(fixtures)
		count, err := recoverWith(context.Background(), testNodeID, fake)
		if err == nil || count != 0 || len(fake.calls) != 1 {
			t.Fatalf("invalid list not rejected before inspect: count=%d err=%v calls=%v", count, err, fake.calls)
		}
	}
	fake := orphanDocker(nil)
	if _, err := recoverWith(context.Background(), "not-a-node", fake); err == nil || len(fake.calls) != 0 {
		t.Fatalf("invalid node reached Docker: err=%v calls=%v", err, fake.calls)
	}
}

func TestRecoveryIsBoundedAndSurfacesRemovalFailure(t *testing.T) {
	items := make([]orphanFixture, maxRecoveryContainers+1)
	for i := range items {
		items[i] = fixture("a", testLeaseID)
		items[i].id = strings.Repeat("a", 63) + string("0123456789abcdef"[i%16])
	}
	fake := orphanDocker(items)
	if count, err := recoverWith(context.Background(), testNodeID, fake); err == nil || count != 0 || len(fake.calls) != 1 {
		t.Fatalf("over-limit recovery not stopped: count=%d err=%v calls=%d", count, err, len(fake.calls))
	}
	fake = orphanDocker([]orphanFixture{fixture("a", testLeaseID)})
	base := fake.handle
	fake.handle = func(args []string) (string, error) {
		if len(args) > 1 && args[1] == "rm" {
			return "", errors.New("daemon refused removal")
		}
		return base(args)
	}
	if count, err := recoverWith(context.Background(), testNodeID, fake); err == nil || count != 0 || !strings.Contains(err.Error(), "daemon refused removal") {
		t.Fatalf("removal failure hidden: count=%d err=%v", count, err)
	}
}

func TestRecoveryLeavesDockerUntouchedWhenNoOrphans(t *testing.T) {
	fake := orphanDocker(nil)
	if count, err := recoverWith(context.Background(), testNodeID, fake); err != nil || count != 0 || len(fake.calls) != 1 {
		t.Fatalf("empty recovery count=%d err=%v calls=%v", count, err, fake.calls)
	}
}

func TestRecoveryStopsOnInspectionError(t *testing.T) {
	fake := orphanDocker([]orphanFixture{fixture("a", testLeaseID)})
	base := fake.handle
	fake.handle = func(args []string) (string, error) {
		if len(args) > 1 && args[1] == "inspect" {
			return "", errors.New("inspect unavailable")
		}
		return base(args)
	}
	if count, err := recoverWith(context.Background(), testNodeID, fake); err == nil || count != 0 ||
		!strings.Contains(err.Error(), "inspect unavailable") || len(fake.calls) != 2 {
		t.Fatalf("inspection failure not closed: count=%d err=%v calls=%v", count, err, fake.calls)
	}
}
