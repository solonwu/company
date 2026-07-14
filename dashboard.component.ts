import { Component, OnInit } from '@angular/core';
import { GiinService } from '../../core/services/giin.service';
import { AccountService } from '../../core/services/account.service';
import { ReportService } from '../../core/services/report.service';
import { KeystoreService } from '../../core/services/keystore.service';
import { GiinConfigDto } from '../../core/models/giin-config.model';
import { FatcaSubmission } from '../../core/models/fatca-submission.model';
import { KeyStoreDto } from '../../core/models/keystore.model';

@Component({
  selector: 'app-dashboard',
  templateUrl: './dashboard.component.html',
  styleUrl: './dashboard.component.scss'
})
export class DashboardComponent implements OnInit {

  readonly currentYear = new Date().getFullYear();

  bankGiin: GiinConfigDto | null = null;
  trustGiin: GiinConfigDto | null = null;
  branchGiin: GiinConfigDto | null = null;
  pendingBankCount: number | null = null;
  pendingTrustCount: number | null = null;
  pendingBranchCount: number | null = null;
  lastSubmission: FatcaSubmission | null = null;
  irsCert: KeyStoreDto | null = null;
  irsCertExpiryDays: number | null = null;

  loadingGiin = true;
  loadingAccounts = true;
  loadingSubmission = true;
  loadingKeystore = true;

  constructor(
    private giinService: GiinService,
    private accountService: AccountService,
    private reportService: ReportService,
    private keystoreService: KeystoreService
  ) {}

  ngOnInit(): void {
    this.giinService.getAll().subscribe({
      next: list => {
        const active = list.filter(g => g.active);
        this.bankGiin   = active.find(g => g.fiType === 'BANK')   ?? null;
        this.trustGiin  = active.find(g => g.fiType === 'TRUST')  ?? null;
        this.branchGiin = active.find(g => g.fiType === 'BRANCH') ?? null;
        this.loadingGiin = false;
      },
      error: () => (this.loadingGiin = false)
    });

    this.accountService.getBankAccounts().subscribe({
      next: page => { this.pendingBankCount = page.totalElements; this.checkAccountsDone(); },
      error: () => this.checkAccountsDone()
    });

    this.accountService.getTrustAccounts().subscribe({
      next: page => { this.pendingTrustCount = page.totalElements; },
      error: () => undefined
    });

    this.accountService.getBranchAccounts().subscribe({
      next: page => { this.pendingBranchCount = page.totalElements; },
      error: () => undefined
    });

    this.reportService.listSubmissions().subscribe({
      next: submissions => {
        this.lastSubmission = submissions.length > 0 ? submissions[0] : null;
        this.loadingSubmission = false;
      },
      error: () => (this.loadingSubmission = false)
    });

    this.keystoreService.getAll().subscribe({
      next: keys => {
        const irsCerts = keys
          .filter(k => k.keyType === 'IRS_CERT' && k.active)
          .sort((a, b) => (b.createdAt > a.createdAt ? 1 : -1));
        this.irsCert = irsCerts.length > 0 ? irsCerts[0] : null;
        this.irsCertExpiryDays = this.irsCert?.validTo ? this.daysUntil(this.irsCert.validTo) : null;
        this.loadingKeystore = false;
      },
      error: () => (this.loadingKeystore = false)
    });
  }

  private checkAccountsDone(): void {
    this.loadingAccounts = false;
  }

  private daysUntil(dateStr: string): number {
    const target = new Date(dateStr).getTime();
    const now = Date.now();
    return Math.ceil((target - now) / (1000 * 60 * 60 * 24));
  }

  get irsCertWarning(): boolean {
    return this.irsCertExpiryDays !== null && this.irsCertExpiryDays >= 0 && this.irsCertExpiryDays <= 30;
  }

  get irsCertExpired(): boolean {
    return this.irsCertExpiryDays !== null && this.irsCertExpiryDays < 0;
  }

  get submissionChipClass(): string {
    if (!this.lastSubmission) {
      return '';
    }
    return this.lastSubmission.status === 'ERROR' ? 'chip-error' : 'chip-ok';
  }
}
